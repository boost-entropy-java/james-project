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

package org.apache.james.jmap.rfc8621.postgres;

import org.apache.james.jmap.rfc8621.contract.MDNSendMethodContract;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.postgres.PostgresMessageId;

public class PostgresMDNSendMethodTest extends PostgresBase implements MDNSendMethodContract {
    public static final PostgresMessageId.Factory MESSAGE_ID_FACTORY = new PostgresMessageId.Factory();

    @Override
    public MessageId randomMessageId() {
        return MESSAGE_ID_FACTORY.generate();
    }
}
