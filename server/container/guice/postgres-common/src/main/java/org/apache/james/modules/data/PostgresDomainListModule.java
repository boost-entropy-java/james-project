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

package org.apache.james.modules.data;

import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.domainlist.lib.DomainListConfiguration;
import org.apache.james.domainlist.postgres.PostgresDomainDataDefinition;
import org.apache.james.domainlist.postgres.PostgresDomainList;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;

public class PostgresDomainListModule extends AbstractModule {
    @Override
    public void configure() {
        bind(PostgresDomainList.class).in(Scopes.SINGLETON);
        bind(DomainList.class).to(PostgresDomainList.class);
        Multibinder.newSetBinder(binder(), PostgresDataDefinition.class).addBinding().toInstance(PostgresDomainDataDefinition.MODULE);
    }

    @ProvidesIntoSet
    InitializationOperation configureDomainList(DomainListConfiguration configuration, PostgresDomainList postgresDomainList) {
        return InitilizationOperationBuilder
            .forClass(PostgresDomainList.class)
            .init(() -> postgresDomainList.configure(configuration));
    }
}
