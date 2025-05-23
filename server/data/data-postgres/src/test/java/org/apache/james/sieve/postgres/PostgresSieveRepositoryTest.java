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

package org.apache.james.sieve.postgres;

import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.backends.postgres.quota.PostgresQuotaCurrentValueDAO;
import org.apache.james.backends.postgres.quota.PostgresQuotaDataDefinition;
import org.apache.james.backends.postgres.quota.PostgresQuotaLimitDAO;
import org.apache.james.sieverepository.api.SieveRepository;
import org.apache.james.sieverepository.lib.SieveRepositoryContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

class PostgresSieveRepositoryTest implements SieveRepositoryContract {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresDataDefinition.aggregateModules(PostgresQuotaDataDefinition.MODULE,
        PostgresSieveDataDefinition.MODULE));

    SieveRepository sieveRepository;

    @BeforeEach
    void setUp() {
        sieveRepository = new PostgresSieveRepository(new PostgresSieveQuotaDAO(new PostgresQuotaCurrentValueDAO(postgresExtension.getDefaultPostgresExecutor()), new PostgresQuotaLimitDAO(postgresExtension.getDefaultPostgresExecutor())),
            new PostgresSieveScriptDAO(postgresExtension.getDefaultPostgresExecutor()));
    }

    @Override
    public SieveRepository sieveRepository() {
        return sieveRepository;
    }
}
