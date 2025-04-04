/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.postgres.filtering;

import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.eventsourcing.eventstore.JsonEventSerializer;
import org.apache.james.eventsourcing.eventstore.postgres.PostgresEventStore;
import org.apache.james.eventsourcing.eventstore.postgres.PostgresEventStoreDAO;
import org.apache.james.eventsourcing.eventstore.postgres.PostgresEventStoreDataDefinition;
import org.apache.james.jmap.api.filtering.FilteringManagement;
import org.apache.james.jmap.api.filtering.FilteringManagementContract;
import org.apache.james.jmap.api.filtering.FilteringRuleSetDefineDTOModules;
import org.apache.james.jmap.api.filtering.impl.EventSourcingFilteringManagement;
import org.junit.jupiter.api.extension.RegisterExtension;

public class PostgresEventSourcingFilteringManagementTest implements FilteringManagementContract {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresDataDefinition.aggregateModules(PostgresFilteringProjectionDataDefinition.MODULE,
        PostgresEventStoreDataDefinition.MODULE));

    @Override
    public FilteringManagement instantiateFilteringManagement() {
        return new EventSourcingFilteringManagement(new PostgresEventStore(new PostgresEventStoreDAO(postgresExtension.getDefaultPostgresExecutor(),
            JsonEventSerializer.forModules(FilteringRuleSetDefineDTOModules.FILTERING_RULE_SET_DEFINED,
                FilteringRuleSetDefineDTOModules.FILTERING_INCREMENT).withoutNestedType())),
            new PostgresFilteringProjection(new PostgresFilteringProjectionDAO(postgresExtension.getDefaultPostgresExecutor())));
    }
}
