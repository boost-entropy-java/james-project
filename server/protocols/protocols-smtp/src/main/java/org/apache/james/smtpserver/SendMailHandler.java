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

package org.apache.james.smtpserver;

import static org.apache.james.smtpserver.futurerelease.FutureReleaseMailParameterHook.FUTURERELEASE_HOLDFOR;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.mail.internet.MimeMessage;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.protocols.api.ProtocolSession;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.dsn.DSNStatus;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.HookReturnCode;
import org.apache.james.queue.api.MailQueue;
import org.apache.james.queue.api.MailQueueFactory;
import org.apache.james.smtpserver.futurerelease.FutureReleaseParameters;
import org.apache.james.util.AuditTrail;
import org.apache.james.util.MDCBuilder;
import org.apache.mailet.Mail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;

/**
 * Queue the message
 */
public class SendMailHandler implements JamesMessageHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(SendMailHandler.class);

    private final MailQueueFactory<?> queueFactory;
    private MailQueue queue;

    @Inject
    public SendMailHandler(MailQueueFactory<?> queueFactory) {
        this.queueFactory = queueFactory;
    }

    @Override
    public void init(Configuration config) {
        queue = queueFactory.createQueue(MailQueueFactory.SPOOL);
    }

    @Override
    public void destroy() {
        try {
            queue.close();
        } catch (IOException e) {
            LOGGER.debug("error close queue", e);
        }
    }

    /**
     * Adds header to the message
     */
    @Override
    public HookResult onMessage(SMTPSession session, Mail mail) {
        LOGGER.debug("sending mail");

        try (Closeable closeable = MDCBuilder.ofValue("messageId", mail.getMessage().getMessageID()).build()) {
            Optional<FutureReleaseParameters.HoldFor> delays = session.getAttachment(FUTURERELEASE_HOLDFOR, ProtocolSession.State.Transaction);

            delays.ifPresentOrElse(Throwing.consumer(holdFor -> {
                    queue.enQueue(mail, holdFor.value());
                    LOGGER.info("Successfully spooled mail {} with messageId {} from {} on {} for {} with delay {}", mail.getName(),
                        mail.getMessage().getMessageID(),
                        mail.getMaybeSender().asString(),
                        session.getRemoteAddress().getAddress(),
                        mail.getRecipients(),
                        holdFor.value());

                    AuditTrail.entry()
                        .username(() -> Optional.ofNullable(session.getUsername())
                            .map(Username::asString)
                            .orElse(""))
                        .remoteIP(() -> Optional.ofNullable(session.getRemoteAddress()))
                        .sessionId(session::getSessionID)
                        .protocol("SMTP")
                        .action("SPOOL")
                        .parameters(Throwing.supplier(() -> ImmutableMap.of("mailId", mail.getName(),
                            "mimeMessageId", Optional.ofNullable(mail.getMessage())
                                .map(Throwing.function(MimeMessage::getMessageID))
                                .orElse(""),
                            "sender", mail.getMaybeSender().asString(),
                            "size", Long.toString(mail.getMessageSize()),
                            "recipients", StringUtils.join(mail.getRecipients()),
                            "holdFor", holdFor.value().toString())))
                        .log("SMTP mail spooled.");
                }),
                Throwing.runnable(() -> {
                    queue.enQueue(mail);
                    LOGGER.info("Successfully spooled mail {} with messageId {} from {} on {} for {}", mail.getName(),
                        mail.getMessage().getMessageID(),
                        mail.getMaybeSender().asString(),
                        session.getRemoteAddress().getAddress(),
                        mail.getRecipients());

                    AuditTrail.entry()
                        .username(() -> Optional.ofNullable(session.getUsername())
                            .map(Username::asString)
                            .orElse(""))
                        .remoteIP(() -> Optional.ofNullable(session.getRemoteAddress()))
                        .sessionId(session::getSessionID)
                        .protocol("SMTP")
                        .action("SPOOL")
                        .parameters(Throwing.supplier(() -> ImmutableMap.of("mailId", mail.getName(),
                            "mimeMessageId", Optional.ofNullable(mail.getMessage())
                                .map(Throwing.function(MimeMessage::getMessageID))
                                .orElse(""),
                            "sender", mail.getMaybeSender().asString(),
                            "size", Long.toString(mail.getMessageSize()),
                            "recipients", StringUtils.join(mail.getRecipients()))))
                        .log("SMTP mail spooled.");
                }));
        } catch (Exception me) {
            LOGGER.error("Unknown error occurred while processing DATA.", me);
            return HookResult.builder()
                .hookReturnCode(HookReturnCode.denySoft())
                .smtpDescription(DSNStatus.getStatus(DSNStatus.TRANSIENT, DSNStatus.UNDEFINED_STATUS) + " Error processing message.")
                .build();
        }
        return HookResult.builder()
            .hookReturnCode(HookReturnCode.ok())
            .smtpDescription(DSNStatus.getStatus(DSNStatus.SUCCESS, DSNStatus.CONTENT_OTHER) + " Message received")
            .build();
    }

}
