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

package ai.grakn.graql.internal.pattern.property;

import ai.grakn.GraknTx;
import ai.grakn.concept.Concept;
import ai.grakn.exception.GraqlQueryException;
import ai.grakn.graql.Var;
import ai.grakn.graql.admin.VarPatternAdmin;
import ai.grakn.graql.admin.VarProperty;
import ai.grakn.graql.internal.gremlin.EquivalentFragmentSet;

import java.util.Collection;
import java.util.stream.Stream;

/**
 * Internal interface for {@link VarProperty}, providing additional methods to match, insert or delete the property.
 *
 * @author Felix Chapman
 */
public interface VarPropertyInternal extends VarProperty {

    /**
     * Check if the given property can be used in a match query
     */
    void checkValid(GraknTx graph, VarPatternAdmin var) throws GraqlQueryException;

    /**
     * Check if the given property can be inserted
     */
    default void checkInsertable(VarPatternAdmin var) throws GraqlQueryException {
    }

    /**
     * Return a collection of {@link EquivalentFragmentSet} to match the given property in the graph
     */
    Collection<EquivalentFragmentSet> match(Var start);

    /**
     * Returns a {@link PropertyExecutor} that describes how to insert the given {@link VarProperty} into.
     *
     * @throws GraqlQueryException if this {@link VarProperty} cannot be inserted
     */
    PropertyExecutor insert(Var var) throws GraqlQueryException;

    PropertyExecutor define(Var var) throws GraqlQueryException;

    /**
     * Whether this property will uniquely identify a concept in the graph, if one exists.
     * This is used for recognising equivalent variables in insert queries.
     */
    default boolean uniquelyIdentifiesConcept() {
        return false;
    }

    /**
     * Delete the given property from the graph, if possible.
     * @param graph the graph to operate on
     * @param concept the concept to delete properties of
     */
    void delete(GraknTx graph, Concept concept) throws GraqlQueryException;

    @Override
    default Stream<VarPatternAdmin> innerVarPatterns() {
        return Stream.empty();
    }

    /**
     * Helper method to perform the safe cast into this internal type
     */
    static VarPropertyInternal from(VarProperty varProperty) {
        return (VarPropertyInternal) varProperty;
    }
}
