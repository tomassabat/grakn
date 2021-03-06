/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.engine;

import ai.grakn.GraknTx;
import ai.grakn.GraknTxType;
import ai.grakn.concept.Attribute;
import ai.grakn.concept.AttributeType;
import ai.grakn.concept.EntityType;
import ai.grakn.concept.Label;
import ai.grakn.concept.Thing;
import ai.grakn.engine.factory.EngineGraknTxFactory;
import ai.grakn.exception.GraknBackendException;
import ai.grakn.exception.GraknTxOperationException;
import ai.grakn.exception.InvalidKBException;
import ai.grakn.kb.admin.GraknAdmin;
import ai.grakn.util.GraknVersion;
import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 * Manages the system keyspace.
 * </p>
 * 
 * <p>
 * Used to populate the system schema the first time the system keyspace
 * is created.
 * </p>
 * 
 * <p>
 * Used to populate the system keyspace with all newly create keyspaces as a
 * user opens them. We have no way to determining whether a keyspace with a
 * given name already exists or not. We maintain the list in our Grakn system
 * keyspace. An element is added to that list when there is an attempt to create
 * a graph from a factory bound to the keyspace name. The list is simply the
 * instances of the system entity type 'keyspace'. Nothing is ever removed from
 * that list. The set of known keyspaces is maintained in a static map so we
 * don't connect to the system keyspace every time a factory produces a new
 * graph. That means that we can't have several different factories (e.g. Janus
 * and in-memory Tinkerpop) at the same time sharing keyspace names. We can't
 * identify the factory builder by engineUrl and config because we don't know
 * what's inside the config, which is residing remotely at the engine!
 * </p>
 * 
 * @author borislav, fppt
 *
 */
public class SystemKeyspace {
    // This will eventually be configurable and obtained the same way the factory is obtained
    // from engine. For now, we just make sure Engine and Core use the same system keyspace name.
    // If there is a more natural home for this constant, feel free to put it there! (Boris)
    public static final String SYSTEM_KB_NAME = "graknSystem";
    private static final String SYSTEM_VERSION = "system-version";
    public static final Label KEYSPACE_ENTITY = Label.of("keyspace");
    public static final Label KEYSPACE_RESOURCE = Label.of("keyspace-name");

    private static final Logger LOG = LoggerFactory.getLogger(SystemKeyspace.class);
    private final ConcurrentHashMap<String, Boolean> openSpaces;
    private final EngineGraknTxFactory factory;

    public SystemKeyspace(EngineGraknTxFactory factory){
        this(factory, true);
    }

    public SystemKeyspace(EngineGraknTxFactory factory, boolean loadSystemSchema){
        this.factory = factory;
        this.openSpaces = new ConcurrentHashMap<>();
        if (loadSystemSchema) {
            loadSystemSchema();
        }
    }

    /**
     * Notify that we just opened a keyspace with the same engineUrl & config.
     */
     public boolean ensureKeyspaceInitialised(String keyspace) {
         if(openSpaces.containsKey(keyspace)){
             return true;
         }

        try (GraknTx graph = factory.tx(SYSTEM_KB_NAME, GraknTxType.WRITE)) {
            AttributeType<String> keyspaceName = graph.getSchemaConcept(KEYSPACE_RESOURCE);
            if (keyspaceName == null) {
                throw GraknBackendException.initializationException(keyspace);
            }
            Attribute<String> attribute = keyspaceName.putAttribute(keyspace);
            if (attribute.owner() == null) {
                graph.<EntityType>getSchemaConcept(KEYSPACE_ENTITY).addEntity().attribute(attribute);
            }
            graph.admin().commitNoLogs();
        } catch (InvalidKBException e) {
            throw new RuntimeException("Could not add keyspace [" + keyspace + "] to system graph", e);
        }

        return true;
    }

    /**
     * Checks if the keyspace exists in the system. The persisted graph is checked each time because the graph
     * may have been deleted in another JVM.
     *
     * @param keyspace The keyspace which might be in the system
     * @return true if the keyspace is in the system
     */
    public boolean containsKeyspace(String keyspace){
        try (GraknTx graph = factory.tx(SYSTEM_KB_NAME, GraknTxType.READ)) {
            return graph.getAttributeType(KEYSPACE_RESOURCE.getValue()).getAttribute(keyspace) != null;
        }
    }

    /**
     * This is called when a graph is deleted via {@link GraknAdmin#delete()}.
     * This removes the keyspace of the deleted graph from the system graph
     *
     * @param keyspace the keyspace to be removed from the system graph
     */
    public boolean deleteKeyspace(String keyspace){
        if(keyspace.equals(SYSTEM_KB_NAME)){
           return false;
        }

        try (GraknTx graph = factory.tx(SYSTEM_KB_NAME, GraknTxType.WRITE)) {
            AttributeType<String> keyspaceName = graph.getSchemaConcept(KEYSPACE_RESOURCE);
            Attribute<String> attribute = keyspaceName.getAttribute(keyspace);

            if(attribute == null) return false;
            Thing thing = attribute.owner();
            if(thing != null) thing.delete();
            attribute.delete();

            openSpaces.remove(keyspace);

            graph.admin().commitNoLogs();
        }

        return true;
    }

    /**
     * Load the system schema into a newly created system keyspace. Because the schema
     * only consists of types, the inserts are idempotent and it is safe to load it
     * multiple times.
     */
    public void loadSystemSchema() {
        Stopwatch timer = Stopwatch.createStarted();
        try (GraknTx tx = factory.tx(SYSTEM_KB_NAME, GraknTxType.WRITE)) {
            if (tx.getSchemaConcept(KEYSPACE_ENTITY) != null) {
                checkVersion(tx);
                return;
            }
            LOG.info("No other version found, loading schema for version {}", GraknVersion.VERSION);
            loadSystemSchema(tx);
            tx.getAttributeType(SYSTEM_VERSION).putAttribute(GraknVersion.VERSION);
            tx.admin().commitNoLogs();
            LOG.info("Loaded system schema to system keyspace. Took: {}", timer.stop());
        } catch (Exception e) {
            LOG.error("Error while loading system schema in {}. The error was: {}", timer.stop(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Helper method which checks the version persisted in the system keyspace with the version of the running grakn
     * instance
     *
     * @throws GraknTxOperationException when the versions do not match
     */
    private void checkVersion(GraknTx tx){
        Attribute existingVersion = tx.getAttributeType(SYSTEM_VERSION).instances().iterator().next();
        if(!GraknVersion.VERSION.equals(existingVersion.getValue())) {
            throw GraknTxOperationException.versionMistmatch(existingVersion);
        } else {
            LOG.info("Found version {}", existingVersion.getValue());
        }
    }

    /**
     * Loads the system schema inside the provided {@link GraknTx}.
     *
     * @param tx The tx to contain the system schema
     */
    private void loadSystemSchema(GraknTx tx){
        //Keyspace data
        AttributeType<String> keyspaceName = tx.putAttributeType("keyspace-name", AttributeType.DataType.STRING);
        tx.putEntityType("keyspace").key(keyspaceName);

        //User Data
        AttributeType<String> userName = tx.putAttributeType("user-name", AttributeType.DataType.STRING);
        AttributeType<String> userPassword = tx.putAttributeType("user-password", AttributeType.DataType.STRING);
        AttributeType<String> userPasswordSalt = tx.putAttributeType("user-password-salt", AttributeType.DataType.STRING);
        AttributeType<String> userFirstName = tx.putAttributeType("user-first-name", AttributeType.DataType.STRING);
        AttributeType<String> userLastName = tx.putAttributeType("user-last-name", AttributeType.DataType.STRING);
        AttributeType<String> userEmail = tx.putAttributeType("user-email", AttributeType.DataType.STRING);
        AttributeType<Boolean> userIsAdmin = tx.putAttributeType("user-is-admin", AttributeType.DataType.BOOLEAN);

        tx.putEntityType("user").key(userName).
                attribute(userPassword).
                attribute(userPasswordSalt).
                attribute(userFirstName).
                attribute(userLastName).
                attribute(userEmail).
                attribute(userIsAdmin);

        //System Version
        tx.putAttributeType("system-version", AttributeType.DataType.STRING);
    }
}