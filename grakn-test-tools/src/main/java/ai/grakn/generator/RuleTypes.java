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
 *
 */

package ai.grakn.generator;

import ai.grakn.concept.RuleType;
import ai.grakn.concept.Label;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;

/**
 * A generator that produces random {@link RuleType}s
 *
 * @author Felix Chapman
 */
public class RuleTypes extends AbstractTypeGenerator<RuleType> {

    public RuleTypes() {
        super(RuleType.class);
    }

    @Override
    protected RuleType newSchemaConcept(Label label) {
        return tx().putRuleType(label);
    }

    @Override
    protected RuleType metaSchemaConcept() {
        return tx().admin().getMetaRuleType();
    }

    @Override
    protected Collection<RuleType> otherMetaSchemaConcepts() {
        return ImmutableSet.of(tx().admin().getMetaRuleInference(), tx().admin().getMetaRuleConstraint());
    }
}
