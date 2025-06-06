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

package org.apache.james.mailbox.postgres.search;

import static jakarta.mail.Flags.Flag.DELETED;
import static org.apache.james.mailbox.postgres.search.SearchOverrideFixture.BLOB_ID;
import static org.apache.james.mailbox.postgres.search.SearchOverrideFixture.MAILBOX;
import static org.apache.james.mailbox.postgres.search.SearchOverrideFixture.MAILBOX_SESSION;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Flags;

import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.blob.api.PlainBlobId;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.postgres.PostgresMailboxAggregateDataDefinition;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.postgres.mail.dao.PostgresMailboxMessageDAO;
import org.apache.james.mailbox.postgres.mail.dao.PostgresMessageDAO;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class NotDeletedWithRangeSearchOverrideTest {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresMailboxAggregateDataDefinition.MODULE);

    private PostgresMailboxMessageDAO postgresMailboxMessageDAO;
    private PostgresMessageDAO postgresMessageDAO;
    private NotDeletedWithRangeSearchOverride testee;

    @BeforeEach
    void setUp() {
        postgresMessageDAO = new PostgresMessageDAO(postgresExtension.getDefaultPostgresExecutor(), new PlainBlobId.Factory());
        postgresMailboxMessageDAO = new PostgresMailboxMessageDAO(postgresExtension.getDefaultPostgresExecutor());
        testee = new NotDeletedWithRangeSearchOverride(postgresExtension.getExecutorFactory());
    }

    @Test
    void undeletedRangeQueryShouldBeApplicable() {
        assertThat(testee.applicable(
            SearchQuery.builder()
                .andCriteria(SearchQuery.flagIsUnSet(DELETED))
                .andCriteria(SearchQuery.uid(new SearchQuery.UidRange(MessageUid.of(4), MessageUid.of(45))))
                .sorts(SearchQuery.DEFAULT_SORTS)
                .build(),
            MAILBOX_SESSION))
            .isTrue();
    }

    @Test
    void notDeletedRangeQueryShouldBeApplicable() {
        assertThat(testee.applicable(
            SearchQuery.builder()
                .andCriteria(SearchQuery.not(SearchQuery.flagIsSet(DELETED)))
                .andCriteria(SearchQuery.uid(new SearchQuery.UidRange(MessageUid.of(4), MessageUid.of(45))))
                .sorts(SearchQuery.DEFAULT_SORTS)
                .build(),
            MAILBOX_SESSION))
            .isTrue();
    }

    @Test
    void sizeQueryShouldNotBeApplicable() {
        assertThat(testee.applicable(
            SearchQuery.builder()
                .andCriteria(SearchQuery.sizeEquals(12))
                .build(),
            MAILBOX_SESSION))
            .isFalse();
    }

    @Test
    void searchShouldReturnEmptyByDefault() {
        assertThat(testee.search(MAILBOX_SESSION, MAILBOX,
            SearchQuery.builder()
                .andCriteria(SearchQuery.not(SearchQuery.flagIsSet(DELETED)))
                .andCriteria(SearchQuery.uid(new SearchQuery.UidRange(MessageUid.of(34), MessageUid.of(345))))
                .build()).collectList().block())
            .isEmpty();
    }

    @Test
    void searchShouldReturnMailboxEntries() {
        MessageUid messageUid = MessageUid.of(1);
        insert(messageUid, MAILBOX.getMailboxId(), new Flags());

        MessageUid messageUid2 = MessageUid.of(2);
        insert(messageUid2, MAILBOX.getMailboxId(), new Flags());

        MessageUid messageUid3 = MessageUid.of(3);
        insert(messageUid3, MAILBOX.getMailboxId(), new Flags(DELETED));

        MessageUid messageUid4 = MessageUid.of(4);
        insert(messageUid4, MAILBOX.getMailboxId(), new Flags());

        MessageUid messageUid5 = MessageUid.of(5);
        insert(messageUid5, MAILBOX.getMailboxId(), new Flags());

        MessageUid messageUid6 = MessageUid.of(6);
        insert(messageUid6, PostgresMailboxId.generate(), new Flags(DELETED));

        assertThat(testee.search(MAILBOX_SESSION, MAILBOX,
            SearchQuery.builder()
                .andCriteria(SearchQuery.not(SearchQuery.flagIsSet(DELETED)))
                .andCriteria(SearchQuery.uid(new SearchQuery.UidRange(messageUid2, messageUid4)))
                .build()).collectList().block())
            .containsOnly(messageUid2, messageUid4);
    }

    private void insert(MessageUid messageUid, MailboxId mailboxId, Flags flags) {
        MailboxMessage message = SearchOverrideFixture.createMessage(messageUid, mailboxId, flags);
        postgresMessageDAO.insert(message, BLOB_ID).block();
        postgresMailboxMessageDAO.insert(message).block();
    }
}
