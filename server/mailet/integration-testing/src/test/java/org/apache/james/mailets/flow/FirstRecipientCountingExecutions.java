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

package org.apache.james.mailets.flow;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.james.core.MailAddress;
import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMatcher;

import com.google.common.collect.ImmutableList;

public class FirstRecipientCountingExecutions extends GenericMatcher {
    private static final AtomicLong executionCount = new AtomicLong();

    public static void reset() {
        executionCount.set(0L);
    }

    public static long executionCount() {
        return executionCount.get();
    }

    @Override
    public Collection<MailAddress> match(Mail mail) {
        executionCount.incrementAndGet();
        return mail.getRecipients().stream()
            .findFirst()
            .map(ImmutableList::of)
            .orElse(ImmutableList.<MailAddress>of());
    }
}
