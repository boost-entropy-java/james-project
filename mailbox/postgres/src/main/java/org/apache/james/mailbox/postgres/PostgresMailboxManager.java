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

package org.apache.james.mailbox.postgres;

import java.time.Clock;
import java.util.EnumSet;

import jakarta.inject.Inject;

import org.apache.james.events.EventBus;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.SessionProvider;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.store.MailboxManagerConfiguration;
import org.apache.james.mailbox.store.NoMailboxPathLocker;
import org.apache.james.mailbox.store.PreDeletionHooks;
import org.apache.james.mailbox.store.StoreMailboxAnnotationManager;
import org.apache.james.mailbox.store.StoreMailboxManager;
import org.apache.james.mailbox.store.StoreMessageManager;
import org.apache.james.mailbox.store.StoreRightManager;
import org.apache.james.mailbox.store.mail.ThreadIdGuessingAlgorithm;
import org.apache.james.mailbox.store.mail.model.impl.MessageParser;
import org.apache.james.mailbox.store.quota.QuotaComponents;
import org.apache.james.mailbox.store.search.MessageSearchIndex;

public class PostgresMailboxManager extends StoreMailboxManager {

    public static final EnumSet<MailboxCapabilities> MAILBOX_CAPABILITIES = EnumSet.of(
        MailboxCapabilities.UserFlag,
        MailboxCapabilities.Namespace,
        MailboxCapabilities.Move,
        MailboxCapabilities.Annotation,
        MailboxCapabilities.ACL);

    private final PostgresMailboxSessionMapperFactory mapperFactory;

    @Inject
    public PostgresMailboxManager(PostgresMailboxSessionMapperFactory mapperFactory,
                                  SessionProvider sessionProvider,
                                  MessageParser messageParser,
                                  MessageId.Factory messageIdFactory,
                                  EventBus eventBus,
                                  StoreMailboxAnnotationManager annotationManager,
                                  StoreRightManager storeRightManager,
                                  QuotaComponents quotaComponents,
                                  MessageSearchIndex index,
                                  ThreadIdGuessingAlgorithm threadIdGuessingAlgorithm,
                                  PreDeletionHooks preDeletionHooks,
                                  Clock clock) {
        super(mapperFactory, sessionProvider, new NoMailboxPathLocker(),
            messageParser, messageIdFactory, annotationManager,
            eventBus, storeRightManager, quotaComponents,
            index, MailboxManagerConfiguration.DEFAULT, preDeletionHooks, threadIdGuessingAlgorithm, clock);
        this.mapperFactory = mapperFactory;
    }

    @Override
    protected StoreMessageManager createMessageManager(Mailbox mailboxRow, MailboxSession session) {
        return new PostgresMessageManager(mapperFactory,
            getMessageSearchIndex(),
            getEventBus(),
            getLocker(),
            mailboxRow,
            getQuotaComponents().getQuotaManager(),
            getQuotaComponents().getQuotaRootResolver(),
            getMessageParser(),
            getMessageIdFactory(),
            configuration.getBatchSizes(),
            getStoreRightManager(),
            getThreadIdGuessingAlgorithm(),
            getClock(),
            getPreDeletionHooks());
    }

    @Override
    public EnumSet<MailboxCapabilities> getSupportedMailboxCapabilities() {
        return MAILBOX_CAPABILITIES;
    }

    @Override
    public EnumSet<MessageCapabilities> getSupportedMessageCapabilities() {
        return EnumSet.of(MessageCapabilities.UniqueID);
    }
}
