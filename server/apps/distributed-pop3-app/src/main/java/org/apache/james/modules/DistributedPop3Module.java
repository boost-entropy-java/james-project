/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.james.modules;

import org.apache.james.backends.cassandra.components.CassandraDataDefinition;
import org.apache.james.events.EventListener;
import org.apache.james.pop3server.mailbox.CassandraPop3MetadataStore;
import org.apache.james.pop3server.mailbox.DistributedMailboxAdapter;
import org.apache.james.pop3server.mailbox.MailboxAdapterFactory;
import org.apache.james.pop3server.mailbox.Pop3MetadataDataDefinition;
import org.apache.james.pop3server.mailbox.Pop3MetadataStore;
import org.apache.james.pop3server.mailbox.PopulateMetadataStoreListener;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;

public class DistributedPop3Module extends AbstractModule {
    @Override
    protected void configure() {
        bind(CassandraPop3MetadataStore.class).in(Scopes.SINGLETON);
        bind(Pop3MetadataStore.class).to(CassandraPop3MetadataStore.class);

        Multibinder.newSetBinder(binder(), EventListener.ReactiveGroupEventListener.class)
            .addBinding()
            .to(PopulateMetadataStoreListener.class);

        bind(DistributedMailboxAdapter.Factory.class).in(Scopes.SINGLETON);
        bind(MailboxAdapterFactory.class).to(DistributedMailboxAdapter.Factory.class);

        Multibinder<CassandraDataDefinition> cassandraDataDefinitions = Multibinder.newSetBinder(binder(), CassandraDataDefinition.class);
        cassandraDataDefinitions.addBinding().toInstance(Pop3MetadataDataDefinition.MODULE);
    }
}