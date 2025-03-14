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

package org.apache.james.smtp;

import static org.apache.james.mailets.configuration.Constants.DEFAULT_DOMAIN;
import static org.apache.james.mailets.configuration.Constants.LOCALHOST_IP;
import static org.apache.james.mailets.configuration.Constants.PASSWORD;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.apache.james.mailets.TemporaryJamesServer;
import org.apache.james.mailets.configuration.SmtpConfiguration;
import org.apache.james.modules.protocols.SmtpGuiceProbe;
import org.apache.james.probe.DataProbe;
import org.apache.james.utils.DataProbeImpl;
import org.apache.james.utils.SMTPMessageSender;
import org.apache.james.utils.SMTPSendingException;
import org.apache.james.utils.SmtpSendingStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;

class SmtpIdentityVerificationTest {
    private static final String ATTACKER_PASSWORD = "secret";

    private static final String ATTACKER = "attacker@" + DEFAULT_DOMAIN;
    private static final String USER = "user@" + DEFAULT_DOMAIN;

    @RegisterExtension
    public SMTPMessageSender messageSender = new SMTPMessageSender(DEFAULT_DOMAIN);

    private TemporaryJamesServer jamesServer;

    private void createJamesServer(File temporaryFolder, SmtpConfiguration.Builder smtpConfiguration) throws Exception {
        jamesServer = TemporaryJamesServer.builder()
            .withSmtpConfiguration(smtpConfiguration)
            .build(temporaryFolder);
        jamesServer.start();

        DataProbe dataProbe = jamesServer.getProbe(DataProbeImpl.class);
        dataProbe.addDomain(DEFAULT_DOMAIN);
        dataProbe.addUser(USER, PASSWORD);
        dataProbe.addUser(ATTACKER, ATTACKER_PASSWORD);
    }

    @AfterEach
    void tearDown() {
        if (jamesServer != null) {
            jamesServer.shutdown();
        }
    }

    @Test
    void remoteUserCanSendEmailsToLocalUsers(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .sendMessage("other@domain.tld", USER);
    }

    @Test
    void relaxedShouldAcceptEmailsFromMXWhenLocalUsers(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .relaxedIdentityVerification());

        messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .sendMessage(USER, USER);
    }

    @Test
    void relaxedShouldRejectEmailsFromMUAWhenLocalUsers(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .relaxedIdentityVerification());

        assertThatThrownBy(() -> new SMTPMessageSender("appleclient").connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .sendMessage(USER, USER)).isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("Error upon step Sender: 530 5.7.1 Authentication Required");
    }

    @Test
    void relaxedShouldRejectEmailsFromMUAIPWhenLocalUsers(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .relaxedIdentityVerification());

        assertThatThrownBy(() -> new SMTPMessageSender("127.0.0.1").connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .sendMessage(USER, USER)).isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("Error upon step Sender: 530 5.7.1 Authentication Required");
    }

    @Test
    void relaxedShouldRejectLocalUsersSpoofingAttempts(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .relaxedIdentityVerification());

        assertThatThrownBy(() -> new SMTPMessageSender("127.0.0.1").connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(USER, PASSWORD)
            .sendMessage("other@james.org", USER)).isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("Error upon step Sender: 503 5.7.1 Incorrect Authentication for Specified Email Address");
    }

    @Test
    void relaxedShouldAcceptEmailsOfAuthenticatedUsers(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .relaxedIdentityVerification());

        new SMTPMessageSender("127.0.0.1").connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(USER, PASSWORD)
            .sendMessage(USER, USER);
    }

    @Test
    void remoteUserCanSendEmailsToLocalUsersWhenAuthNotRequired(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .verifyIdentity());

        messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .sendMessage("other@domain.tld", USER);
    }

    @Test
    void spoofingForbiddenWhenNoAuthRequired(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .verifyIdentity());

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .sendMessage(USER, USER))
            .isEqualTo(new SMTPSendingException(SmtpSendingStep.Sender, "530 5.7.1 Authentication Required\n"));
    }

    @Test
    void smtpShouldAcceptMessageWhenIdentityIsMatching(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(USER, PASSWORD).sendMessage(USER, USER);
    }

    @Test
    void spoofingAttemptsShouldBeRejectedInFromField(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: victim@spoofed.info\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("503 5.7.1 Incorrect Authentication for Specified Email Address");
    }

    @Test
    void spoofingAttemptsShouldBeRejectedInFromFieldWhenGroup(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: MyGroup: victim@spoofed.info;\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("503 5.7.1 Incorrect Authentication for Specified Email Address");
    }

    @Test
    void spoofingAttemptsShouldBeRejectedInFromFieldWhenGroupWithOtherUsers(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: MyGroup: user@james.org, victim@spoofed.info;\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("503 5.7.1 Incorrect Authentication for Specified Email Address");
    }

    @Test
    void shouldRejectEmptyGroups(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: MyGroup: ;\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("503 5.7.1 Incorrect Authentication for Specified Email Address");
    }

    @Test
    void shouldRejectInvalidWhenLocalUser(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: INVALID\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("503 5.7.1 Incorrect Authentication for Specified Email Address");
    }

    @Test
    void shouldAcceptGroupWhenUnauthenticated(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: MyGroup: victim@spoofed.info;\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatCode(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .sendMessageWithHeaders("victim@spoofed.info", ImmutableList.of(USER), message))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptInvalidFromWhenUnauthenticated(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: INVALID\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatCode(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .sendMessageWithHeaders("victim@spoofed.info", ImmutableList.of(USER), message))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptGroupWhenOnlyUser(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: MyGroup: user@james.org;\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatCode(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .doesNotThrowAnyException();
    }

    @Test
    void messageWithMissingMimeMessageFromFieldShouldBeRejected(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("503 5.5.4 Missing From header");
    }

    @Test
    void spoofingInternalAddressAttemptsShouldBeRejectedInFromField(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        String message = """
            FROM: victim@james.org\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageWithHeaders(USER, ImmutableList.of(USER), message))
            .isInstanceOf(SMTPSendingException.class)
            .hasMessageContaining("503 5.7.1 Incorrect Authentication for Specified Email Address");
    }

    @Test
    void aliasShouldBeSupportedInFromField(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        jamesServer.getProbe(DataProbeImpl.class)
            .addUserAliasMapping("alias", DEFAULT_DOMAIN, USER);

        String message = """
            FROM: alias@james.org\r
            subject: test\r
            \r
            content\r
            .\r
            """;

        messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(USER, PASSWORD)
            .sendMessageWithHeaders(USER, ImmutableList.of(USER), message);
    }

    @Test
    void verifyIdentityShouldRejectNullSenderWHenAuthenticated(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(USER, PASSWORD)
                .sendMessageNoSender(USER, USER))
            .isEqualTo(new SMTPSendingException(SmtpSendingStep.Sender, "503 5.7.1 Incorrect Authentication for Specified Email Address\n"));
    }

    @Test
    void verifyIdentityShouldAcceptNullSenderWhenAuthenticationRequired(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        assertThatCode(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .sendMessageNoSender(USER, USER))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectUnauthenticatedSendersUsingLocalDomains(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .sendMessage(USER, USER))
            .isEqualTo(new SMTPSendingException(SmtpSendingStep.Sender, "530 5.7.1 Authentication Required\n"));
    }

    @Test
    void smtpShouldAcceptMessageWhenIdentityIsNotMatchingButNotChecked(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .doNotVerifyIdentity());

        messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(ATTACKER, ATTACKER_PASSWORD)
            .sendMessage(USER, USER);
    }

    @Test
    void smtpShouldRejectMessageWhenIdentityIsNotMatching(@TempDir File temporaryFolder) throws Exception {
        createJamesServer(temporaryFolder, SmtpConfiguration.builder()
            .requireAuthentication()
            .verifyIdentity());

        assertThatThrownBy(() ->
            messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
                .authenticate(ATTACKER, ATTACKER_PASSWORD)
                .sendMessage(USER, USER))
            .isEqualTo(new SMTPSendingException(SmtpSendingStep.Sender, "503 5.7.1 Incorrect Authentication for Specified Email Address\n"));
    }
}
