/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 */
package org.neo4j.graphdb.factory.module.edition;

import org.neo4j.cypher.internal.javacompat.DozerDbCypherEngineProvider;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.impl.query.QueryEngineProvider;

/**
 * Thin wrapper around the current community edition module.
 *
 * Neo4j 2026.03.x moved several internal extension points used by the old
 * custom implementation. Delegating to upstream keeps this bootstrap class
 * compatible while the deeper custom integrations are migrated separately.
 */
public class DozerDbEditionModule extends CommunityEditionModule {
    public DozerDbEditionModule(GlobalModule globalModule) {
        super(globalModule);
    }

    @Override
    public QueryEngineProvider queryEngineProvider() {
        return new DozerDbCypherEngineProvider();
    }
}
