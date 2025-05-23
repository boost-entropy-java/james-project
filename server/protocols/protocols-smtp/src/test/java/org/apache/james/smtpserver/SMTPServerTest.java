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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import jakarta.mail.internet.MimeMessage;

import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.smtp.SMTPClient;
import org.apache.commons.net.smtp.SMTPReply;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.protocols.netty.AbstractChannelPipelineFactory;
import org.apache.james.rrt.lib.MappingSource;
import org.apache.james.server.core.configuration.Configuration;
import org.apache.mailet.Mail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

public class SMTPServerTest {
    public static final String LOCAL_DOMAIN = "example.local";
    public static final String USER_LOCALHOST = "test_user_smtp@localhost";
    public static final String USER_LOCAL_DOMAIN = "test_user_smtp@example.local";

    static final class AlterableDNSServer implements DNSService {

        private InetAddress localhostByName = null;

        @Override
        public Collection<String> findMXRecords(String hostname) {
            List<String> res = new ArrayList<>();
            if (hostname == null) {
                return res;
            }
            if ("james.apache.org".equals(hostname)) {
                res.add("nagoya.apache.org");
            }
            return res;
        }

        @Override
        public Collection<InetAddress> getAllByName(String host) throws UnknownHostException {
            return ImmutableList.of(getByName(host));
        }

        @Override
        public InetAddress getByName(String host) throws UnknownHostException {
            if (getLocalhostByName() != null) {
                if ("127.0.0.1".equals(host)) {
                    return getLocalhostByName();
                }
            }

            if ("1.0.0.127.bl.spamcop.net.".equals(host)) {
                return InetAddress.getByName(Domain.LOCALHOST.asString());
            }

            if ("james.apache.org".equals(host)) {
                return InetAddress.getByName("james.apache.org");
            }

            if ("abgsfe3rsf.de".equals(host)) {
                throw new UnknownHostException();
            }

            return InetAddress.getByName(host);
        }

        @Override
        public Collection<String> findTXTRecords(String hostname) {
            List<String> res = new ArrayList<>();
            if (hostname == null) {
                return res;
            }

            if ("2.0.0.127.bl.spamcop.net.".equals(hostname)) {
                res.add("Blocked - see http://www.spamcop.net/bl.shtml?127.0.0.2");
            }
            return res;
        }

        public InetAddress getLocalhostByName() {
            return localhostByName;
        }

        public void setLocalhostByName(InetAddress localhostByName) {
            this.localhostByName = localhostByName;
        }

        @Override
        public String getHostName(InetAddress addr) {
            return addr.getHostName();
        }

        @Override
        public InetAddress getLocalHost() throws UnknownHostException {
            return InetAddress.getLocalHost();
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SMTPServerTest.class);

    protected SMTPTestConfiguration smtpConfiguration = new SMTPTestConfiguration();
    protected Configuration configuration;
    private final SMTPServerTestSystem testSystem = new SMTPServerTestSystem();

    @BeforeEach
    public void setUp() throws Exception {
        testSystem.preSetUp();
    }

    protected void init(SMTPTestConfiguration testConfiguration) throws Exception {
        testConfiguration.init();
        initSMTPServer(testConfiguration);
    }

    protected void initSMTPServer(SMTPTestConfiguration testConfiguration) throws Exception {
        testSystem.smtpServer.configure(testConfiguration);
        testSystem.smtpServer.init();
    }

    @Test
    public void testMaxLineLength() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        StringBuilder sb = new StringBuilder();
        sb.append("A".repeat(AbstractChannelPipelineFactory.MAX_LINE_LENGTH));
        smtpProtocol.sendCommand("EHLO " + sb.toString());
        System.out.println(smtpProtocol.getReplyString());
        assertThat(smtpProtocol.getReplyCode())
            .as("Line length exceed")
            .isEqualTo(500);

        smtpProtocol.sendCommand("EHLO test");
        assertThat(smtpProtocol.getReplyCode())
            .as("Line length ok")
            .isEqualTo(250);

        smtpProtocol.quit();
        smtpProtocol.disconnect();
    }

    @Test
    public void testConnectionLimit() throws Exception {
        smtpConfiguration.setConnectionLimit(2);
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());
        SMTPClient smtpProtocol2 = new SMTPClient();
        smtpProtocol2.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        SMTPClient smtpProtocol3 = new SMTPClient();

        try {
            smtpProtocol3.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());
            Thread.sleep(3000);
            fail("Shold disconnect connection 3");
        } catch (Exception e) {
            LOGGER.info("Ignored error", e);
        }

        ensureIsDisconnected(smtpProtocol);
        ensureIsDisconnected(smtpProtocol2);

        smtpProtocol3.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());
        Thread.sleep(3000);

    }

    private void ensureIsDisconnected(SMTPClient client) throws IOException {
        client.quit();
        client.disconnect();
    }

    @AfterEach
    public void tearDown() {
        if (testSystem.smtpServer.isStarted()) {
            testSystem.smtpServer.destroy();
        }
    }

    public void verifyLastMail(String sender, String recipient, MimeMessage msg) throws Exception {
        Mail mailData = testSystem.queue.getLastMail();
        assertThat(mailData)
            .as("mail received by mail server")
            .isNotNull();

        if (sender == null && recipient == null && msg == null) {
            fail("no verification can be done with all arguments null");
        }

        if (sender != null) {
            assertThat(mailData.getMaybeSender().asString())
                .as("sender verfication")
                .isEqualTo(sender);
        }
        if (recipient != null) {
            assertThat(mailData.getRecipients())
                .as("recipient verfication")
                .containsOnly(new MailAddress(recipient));
        }
        if (msg != null) {
            ByteArrayOutputStream bo1 = new ByteArrayOutputStream();
            msg.writeTo(bo1);
            ByteArrayOutputStream bo2 = new ByteArrayOutputStream();
            mailData.getMessage().writeTo(bo2);
            assertThat(bo2.toString()).isEqualTo(bo1.toString());
            assertThat(mailData.getMessage())
                .as("message verification")
                .isEqualTo(msg);
        }
    }

    @Test
    public void testSimpleMailSendWithEHLO() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol.sendCommand("EHLO localhost");
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        assertThat(capabilitieslist)
            .as("capabilities")
            .hasSize(3);
        assertThat(capabilitieslist.contains("PIPELINING"))
            .as("capabilities present PIPELINING")
            .isTrue();
        assertThat(capabilitieslist.contains("ENHANCEDSTATUSCODES"))
            .as("capabilities present ENHANCEDSTATUSCODES")
            .isTrue();
        assertThat(capabilitieslist.contains("8BITMIME"))
            .as("capabilities present 8BITMIME")
            .isTrue();

        smtpProtocol.setSender("mail@localhost");
        smtpProtocol.addRecipient("mail@localhost");

        smtpProtocol.sendShortMessageData("Subject: test\r\n\r\nBody\r\n\r\n.\r\n");
        smtpProtocol.quit();
        smtpProtocol.disconnect();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNotNull();
    }

    @Test
    public void messageExceedingMessageSizeShouldBeDiscarded() throws Exception {
        // Given
        init(smtpConfiguration);
        int maxSize = 1024;
        testSystem.smtpServer.setMaximalMessageSize(maxSize);

        // When
        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());
        smtpProtocol.sendCommand("EHLO " + InetAddress.getLocalHost());
        smtpProtocol.setSender("mail@localhost");
        smtpProtocol.addRecipient("mail@localhost");
        // Create a 1K+ message
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Subject: test\r\n\r\n");
        String repeatedString = "This is the repeated body...\r\n";
        int repeatCount = (maxSize / repeatedString.length()) + 1;
        stringBuilder.append(repeatedString.repeat(repeatCount));
        stringBuilder.append("\r\n\r\n.\r\n");
        smtpProtocol.sendShortMessageData(stringBuilder.toString());

        // Then
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNull();

        // finally
        smtpProtocol.quit();
        smtpProtocol.disconnect();
    }

    @Test
    public void messageExceedingMessageSizeShouldBeRespondedAsOverQuota() throws Exception {
        // Given
        init(smtpConfiguration);
        int maxSize = 1024;
        testSystem.smtpServer.setMaximalMessageSize(maxSize);

        //When
        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());
        smtpProtocol.sendCommand("EHLO localhost");
        smtpProtocol.setSender("mail@localhost");
        smtpProtocol.addRecipient("mail@localhost");
        // Create a 1K+ message
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Subject: test\r\n\r\n");
        String repeatedString = "This is the repeated body...\r\n";
        int repeatCount = (maxSize / repeatedString.length()) + 1;
        stringBuilder.append(repeatedString.repeat(repeatCount));
        stringBuilder.append("\r\n\r\n.\r\n");
        smtpProtocol.sendShortMessageData(stringBuilder.toString());

        // Then
        assertThat(smtpProtocol.getReplyString()).isEqualTo("552 Quota exceeded\r\n");

        // Finally
        smtpProtocol.quit();
        smtpProtocol.disconnect();
    }

    @Test
    public void testStartTLSInEHLO() throws Exception {
        smtpConfiguration.setStartTLS();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol.sendCommand("EHLO localhost");
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        assertThat(capabilitieslist)
            .as("capabilities")
            .hasSize(4);
        assertThat(capabilitieslist)
            .as("capabilities present PIPELINING ENHANCEDSTATUSCODES 8BITMIME STARTTLS")
            .containsOnly("PIPELINING", "ENHANCEDSTATUSCODES", "8BITMIME", "STARTTLS");

        smtpProtocol.quit();
        smtpProtocol.disconnect();

    }

    @Test
    public void startTlsCommandShouldWorkWhenAlone() throws Exception {
        smtpConfiguration.setStartTLS();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol.sendCommand("EHLO " + InetAddress.getLocalHost());
        smtpProtocol.sendCommand("STARTTLS");
        assertThat(smtpProtocol.getReplyCode()).isEqualTo(220);
        
        smtpProtocol.disconnect();
    }

    @Test
    public void startTlsCommandShouldFailWhenFollowedByInjectedCommand() throws Exception {
        smtpConfiguration.setStartTLS();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol.sendCommand("EHLO " + InetAddress.getLocalHost());
        smtpProtocol.sendCommand("STARTTLS\r\nAUTH PLAIN");
        assertThat(smtpProtocol.getReplyCode()).isEqualTo(451);
        
        smtpProtocol.disconnect();
    }

    protected SMTPClient newSMTPClient() throws IOException {
        SMTPClient smtp = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtp.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());
        if (LOGGER.isDebugEnabled()) {
            smtp.addProtocolCommandListener(new ProtocolCommandListener() {

                @Override
                public void protocolCommandSent(ProtocolCommandEvent event) {
                    LOGGER.debug("> {}", event.getMessage().trim());
                }

                @Override
                public void protocolReplyReceived(ProtocolCommandEvent event) {
                    LOGGER.debug("< {}", event.getMessage().trim());
                }
            });
        }
        return smtp;
    }

    @Test
    public void testReceivedHeader() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtp = newSMTPClient();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtp.helo("localhost");
        smtp.setSender("mail@localhost");
        smtp.addRecipient("mail@localhost");
        smtp.sendShortMessageData("Subject: test\r\n\r\n");

        smtp.quit();
        smtp.disconnect();

        assertThat(testSystem.queue.getLastMail().getMessage().getHeader("Received"))
            .as("spooled mail has Received header")
            .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"good", "adomain.com", "sub.domain.com",  "127.0.0.1",  "[127.0.0.1]",
        "fe80::1ff:fe23:4567:890a", "[fe80::1ff:fe23:4567:890a]",
        "2001:db8:85a3:8d3:1319:8a2e:370:7348", "[2001:db8:85a3:8d3:1319:8a2e:370:7348]"})
    public void testValidHELO(String helo) throws Exception {
        init(smtpConfiguration);

        SMTPClient smtp = newSMTPClient();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtp.helo(helo);

        assertThat(smtp.getReplyString())
            .startsWith("250");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad value", "localhost) (spoofed into received header", "localhost)", "127.0.0.1/24",
        "sub..bad", "[]", ""})
    public void testInvalidHELO(String helo) throws Exception {
        init(smtpConfiguration);

        SMTPClient smtp = newSMTPClient();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtp.helo(helo);

        assertThat(smtp.getReplyString())
            .startsWith("501");
    }

    @ParameterizedTest
    @ValueSource(strings = {"good", "127.0.0.1", "adomain.com", "sub.domain.com", "[abc.def]", "[124.54.67.43]",
        "fe80::1ff:fe23:4567:890a", "[fe80::1ff:fe23:4567:890a]",
        "2001:db8:85a3:8d3:1319:8a2e:370:7348", "[2001:db8:85a3:8d3:1319:8a2e:370:7348]",
        "IPV6:2a01:cb06:b841:bf5d:38ff:861a:1646:ab00", "ipv6:2a01:cb06:b841:bf5d:38ff:861a:1646:ab00",
        "[IPV6:2a01:cb06:b841:bf5d:38ff:861a:1646:ab00]", "[ipv6:2a01:cb06:b841:bf5d:38ff:861a:1646:ab00]"})
    public void testValidEHLO(String helo) throws Exception {
        init(smtpConfiguration);

        SMTPClient smtp = newSMTPClient();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtp.sendCommand("EHLO " + helo);

        assertThat(smtp.getReplyString())
            .startsWith("250");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad value", "localhost) (spoofed into received header", "localhost)", "127.0.0.1/24", "sub..bad", "[]"})
    public void testInvalidEHLO(String helo) throws Exception {
        init(smtpConfiguration);

        SMTPClient smtp = newSMTPClient();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtp.sendCommand("EHLO " + helo);

        assertThat(smtp.getReplyString())
            .startsWith("501 5.5.2 Invalid domain name or ip supplied as HELO argument");
    }

    // FIXME
    @Disabled
    @Test
    public void testEmptyMessageReceivedHeader() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtp = newSMTPClient();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtp.helo(InetAddress.getLocalHost().toString());
        smtp.setSender("mail@localhost");
        smtp.addRecipient("mail@localhost");
        smtp.sendShortMessageData("");

        smtp.quit();
        smtp.disconnect();

        assertThat(testSystem.queue.getLastMail().getMessage().getHeader("Received"))
            .as("spooled mail has Received header")
            .isNotNull();
        // TODO: test body size
    }

    @Test
    public void testSimpleMailSendWithHELO() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol.helo("localhost");

        smtpProtocol.setSender("mail@localhost");

        smtpProtocol.addRecipient("mail@localhost");

        smtpProtocol.sendShortMessageData("Subject: test mail\r\n\r\nTest body testSimpleMailSendWithHELO\r\n.\r\n");

        smtpProtocol.quit();
        smtpProtocol.disconnect();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNotNull();
    }

    @Test
    public void testTwoSimultaneousMails() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());
        SMTPClient smtpProtocol2 = new SMTPClient();
        smtpProtocol2.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();
        assertThat(smtpProtocol2.isConnected())
            .as("second connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol1.helo("localhost");
        smtpProtocol2.helo("localhost");

        String sender1 = "mail_sender1@localhost";
        String recipient1 = "mail_recipient1@localhost";
        smtpProtocol1.setSender(sender1);
        smtpProtocol1.addRecipient(recipient1);

        String sender2 = "mail_sender2@localhost";
        String recipient2 = "mail_recipient2@localhost";
        smtpProtocol2.setSender(sender2);
        smtpProtocol2.addRecipient(recipient2);

        smtpProtocol1.sendShortMessageData("Subject: test\r\n\r\nTest body testTwoSimultaneousMails1\r\n.\r\n");
        verifyLastMail(sender1, recipient1, null);

        smtpProtocol2.sendShortMessageData("Subject: test\r\n\r\nTest body testTwoSimultaneousMails2\r\n.\r\n");
        verifyLastMail(sender2, recipient2, null);

        smtpProtocol1.quit();
        smtpProtocol2.quit();

        smtpProtocol1.disconnect();
        smtpProtocol2.disconnect();
    }

    @Test
    public void testTwoMailsInSequence() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol1.helo("localhost");

        String sender1 = "mail_sender1@localhost";
        String recipient1 = "mail_recipient1@localhost";
        smtpProtocol1.setSender(sender1);
        smtpProtocol1.addRecipient(recipient1);

        smtpProtocol1.sendShortMessageData("Subject: test\r\n\r\nTest body testTwoMailsInSequence1\r\n");
        verifyLastMail(sender1, recipient1, null);

        String sender2 = "mail_sender2@localhost";
        String recipient2 = "mail_recipient2@localhost";
        smtpProtocol1.setSender(sender2);
        smtpProtocol1.addRecipient(recipient2);

        smtpProtocol1.sendShortMessageData("Subject: test2\r\n\r\nTest body2 testTwoMailsInSequence2\r\n");
        verifyLastMail(sender2, recipient2, null);

        smtpProtocol1.quit();
        smtpProtocol1.disconnect();
    }

    @Test
    public void testHeloResolv() throws Exception {
        smtpConfiguration.setHeloResolv();
        smtpConfiguration.setAuthorizedAddresses("192.168.0.1");
        init(smtpConfiguration);

        doTestHeloEhloResolv("helo");
    }

    private void doTestHeloEhloResolv(String heloCommand) throws Exception {
        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        String fictionalDomain = "abgsfe3rsf.de";
        String existingDomain = "james.apache.org";
        String mail = "sender@james.apache.org";
        String rcpt = "rcpt@localhost";

        smtpProtocol.sendCommand(heloCommand, fictionalDomain);
        smtpProtocol.setSender(mail);

        // this should give a 501 code cause the helo/ehlo could not resolved
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error: " + heloCommand + " could not resolved")
            .isEqualTo(501);

        smtpProtocol.sendCommand(heloCommand, existingDomain);
        smtpProtocol.setSender(mail);
        smtpProtocol.addRecipient(rcpt);

        if (smtpProtocol.getReplyCode() == 501) {
            fail(existingDomain + " domain currently cannot be resolved (check your DNS/internet connection/proxy settings to make test pass)");
        }
        // helo/ehlo is resolvable. so this should give a 250 code
        assertThat(smtpProtocol.getReplyCode())
            .as(heloCommand + " accepted")
            .isEqualTo(250);

        smtpProtocol.quit();
    }

    @Test
    public void testHeloResolvDefault() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol1.helo("abgsfe3rsf.de");
        // helo should not be checked. so this should give a 250 code
        assertThat(smtpProtocol1.getReplyCode())
            .as("Helo accepted")
            .isEqualTo(250);

        smtpProtocol1.quit();
    }

    @Test
    public void testReverseEqualsHelo() throws Exception {
        smtpConfiguration.setReverseEqualsHelo();
        smtpConfiguration.setAuthorizedAddresses("192.168.0.1");
        // temporary alter the loopback resolution
        try {
            testSystem.dnsServer.setLocalhostByName(InetAddress.getByName("james.apache.org"));
        } catch (UnknownHostException e) {
            fail("james.apache.org currently cannot be resolved (check your DNS/internet connection/proxy settings to make test pass)");
        }
        try {
            init(smtpConfiguration);

            SMTPClient smtpProtocol1 = new SMTPClient();
            InetSocketAddress bindedAddress = testSystem.getBindedAddress();
            smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

            assertThat(smtpProtocol1.isConnected())
                .as("first connection taken")
                .isTrue();

            // no message there, yet
            assertThat(testSystem.queue.getLastMail())
                .as("no mail received by mail server")
                .isNull();

            String helo1 = "abgsfe3rsf.de";
            String helo2 = "james.apache.org";
            String mail = "sender";
            String rcpt = "recipient";

            smtpProtocol1.sendCommand("helo", helo1);
            smtpProtocol1.setSender(mail);

            // this should give a 501 code cause the helo not equal reverse of
            // ip
            assertThat(smtpProtocol1.getReplyCode())
                .as("expected error: helo not equals reverse of ip")
                .isEqualTo(501);

            smtpProtocol1.sendCommand("helo", helo2);
            smtpProtocol1.setSender(mail);
            smtpProtocol1.addRecipient(rcpt);

            // helo is resolvable. so this should give a 250 code
            assertThat(smtpProtocol1.getReplyCode())
                .as("Helo accepted")
                .isEqualTo(250);

            smtpProtocol1.quit();
        } finally {
            testSystem.dnsServer.setLocalhostByName(null);
        }
    }

    @Test
    public void testSenderDomainResolv() throws Exception {
        smtpConfiguration.setSenderDomainResolv();
        smtpConfiguration.setAuthorizedAddresses("192.168.0.1/32");
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol1.helo("localhost");

        String sender1 = "mail_sender1@xfwrqqfgfe.de";

        smtpProtocol1.setSender(sender1);
        assertThat(smtpProtocol1.getReplyCode())
            .as("expected 501 error")
            .isEqualTo(501);

        smtpProtocol1.addRecipient("test@localhost");
        assertThat(smtpProtocol1.getReplyCode())
            .as("Recipient not accepted cause no valid sender")
            .isEqualTo(503);
        smtpProtocol1.quit();

    }

    @Test
    public void testSenderDomainResolvDefault() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol1.helo(InetAddress.getLocalHost().toString());

        String sender1 = "mail_sender1@xfwrqqfgfe.de";

        smtpProtocol1.setSender(sender1);

        smtpProtocol1.quit();
    }

    @Test
    public void testSenderDomainResolvRelayClientDefault() throws Exception {
        smtpConfiguration.setSenderDomainResolv();
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol1.helo(InetAddress.getLocalHost().toString());

        String sender1 = "mail_sender1@xfwrqqfgfe.de";

        // Both mail shold
        smtpProtocol1.setSender(sender1);

        smtpProtocol1.quit();

    }

    @Test
    public void testSenderDomainResolvRelayClient() throws Exception {
        smtpConfiguration.setSenderDomainResolv();
        smtpConfiguration.setCheckAuthNetworks(true);
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol1.helo("localhost");

        String sender1 = "mail_sender1@xfwrqqfgfe.de";
        String sender2 = "mail_sender2@james.apache.org";

        smtpProtocol1.setSender(sender1);
        assertThat(smtpProtocol1.getReplyCode())
            .as("expected 501 error")
            .isEqualTo(501);

        smtpProtocol1.setSender(sender2);

        smtpProtocol1.quit();

    }

    @Test
    public void testMaxRcpt() throws Exception {
        smtpConfiguration.setMaxRcpt(1);
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        smtpProtocol1.helo("localhost");

        String sender1 = "mail_sender1@james.apache.org";
        String rcpt1 = "test@localhost";
        String rcpt2 = "test2@localhost";

        smtpProtocol1.setSender(sender1);
        smtpProtocol1.addRecipient(rcpt1);

        smtpProtocol1.addRecipient(rcpt2);
        assertThat(smtpProtocol1.getReplyCode())
            .as("expected 452 error")
            .isEqualTo(452);

        smtpProtocol1.sendShortMessageData("Subject: test\r\n\r\nTest body testMaxRcpt1\r\n");

        // After the data is send the rcpt count is set back to 0.. So a new
        // mail with rcpt should be accepted

        smtpProtocol1.setSender(sender1);

        smtpProtocol1.addRecipient(rcpt1);

        smtpProtocol1.sendShortMessageData("Subject: test\r\n\r\nTest body testMaxRcpt2\r\n");

        smtpProtocol1.quit();

    }

    @Test
    public void testMaxRcptDefault() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol1.helo(InetAddress.getLocalHost().toString());

        String sender1 = "mail_sender1@james.apache.org";
        String rcpt1 = "test@localhost";

        smtpProtocol1.setSender(sender1);

        smtpProtocol1.addRecipient(rcpt1);

        smtpProtocol1.sendShortMessageData("Subject: test\r\n\r\nTest body testMaxRcptDefault\r\n");

        smtpProtocol1.quit();
    }

    @Test
    public void testEhloResolv() throws Exception {
        smtpConfiguration.setEhloResolv();
        smtpConfiguration.setAuthorizedAddresses("192.168.0.1");
        init(smtpConfiguration);

        doTestHeloEhloResolv("ehlo");
    }

    @Test
    public void testEhloResolvDefault() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol1.sendCommand("ehlo", "abgsfe3rsf.de");
        // ehlo should not be checked. so this should give a 250 code
        assertThat(smtpProtocol1.getReplyCode())
            .as("ehlo accepted")
            .isEqualTo(250);

        smtpProtocol1.quit();
    }

    @Test
    public void testEhloResolvIgnoreClientDisabled() throws Exception {
        smtpConfiguration.setEhloResolv();
        smtpConfiguration.setCheckAuthNetworks(true);
        init(smtpConfiguration);

        doTestHeloEhloResolv("ehlo");
    }

    @Test
    public void testReverseEqualsEhlo() throws Exception {
        smtpConfiguration.setReverseEqualsEhlo();
        smtpConfiguration.setAuthorizedAddresses("192.168.0.1");
        // temporary alter the loopback resolution
        InetAddress jamesDomain = null;
        try {
            jamesDomain = testSystem.dnsServer.getByName("james.apache.org");
        } catch (UnknownHostException e) {
            fail("james.apache.org currently cannot be resolved (check your DNS/internet connection/proxy settings to make test pass)");
        }
        testSystem.dnsServer.setLocalhostByName(jamesDomain);
        try {
            init(smtpConfiguration);

            SMTPClient smtpProtocol1 = new SMTPClient();
            InetSocketAddress bindedAddress = testSystem.getBindedAddress();
            smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

            assertThat(smtpProtocol1.isConnected())
                .as("first connection taken")
                .isTrue();
            // no message there, yet
            assertThat(testSystem.queue.getLastMail())
                .as("no mail received by mail server")
                .isNull();

            String ehlo1 = "abgsfe3rsf.de";
            String ehlo2 = "james.apache.org";
            String mail = "sender";
            String rcpt = "recipient";

            smtpProtocol1.sendCommand("ehlo", ehlo1);
            smtpProtocol1.setSender(mail);

            // this should give a 501 code cause the ehlo not equals reverse of
            // ip
            assertThat(smtpProtocol1.getReplyCode())
                .as("expected error: ehlo not equals reverse of ip")
                .isEqualTo(501);

            smtpProtocol1.sendCommand("ehlo", ehlo2);
            smtpProtocol1.setSender(mail);
            smtpProtocol1.addRecipient(rcpt);

            // ehlo is resolvable. so this should give a 250 code
            assertThat(smtpProtocol1.getReplyCode())
                .as("ehlo accepted")
                .isEqualTo(250);

            smtpProtocol1.quit();
        } finally {
            testSystem.dnsServer.setLocalhostByName(null);
        }
    }

    @Test
    public void testHeloEnforcement() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        String sender1 = "mail_sender1@localhost";
        smtpProtocol1.setSender(sender1);
        assertThat(smtpProtocol1.getReplyCode())
            .as("expected 503 error")
            .isEqualTo(503);

        smtpProtocol1.helo(InetAddress.getLocalHost().toString());

        smtpProtocol1.setSender(sender1);

        smtpProtocol1.quit();
    }

    @Test
    public void testHeloEnforcementDisabled() throws Exception {
        smtpConfiguration.setHeloEhloEnforcement(false);
        init(smtpConfiguration);

        SMTPClient smtpProtocol1 = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol1.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        assertThat(smtpProtocol1.isConnected())
            .as("first connection taken")
            .isTrue();

        // no message there, yet
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();

        String sender1 = "mail_sender1@localhost";

        smtpProtocol1.setSender(sender1);

        smtpProtocol1.quit();
    }

    @Test
    public void testAuthCancel() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("127.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", InetAddress.getLocalHost().toString());

        smtpProtocol.sendCommand("AUTH PLAIN");

        assertThat(smtpProtocol.getReplyCode())
            .as("start auth.")
            .isEqualTo(334);

        smtpProtocol.sendCommand("*");

        assertThat(smtpProtocol.getReplyCode())
            .as("cancel auth.")
            .isEqualTo(501);

        smtpProtocol.quit();

    }

    // Test for JAMES-939
    @Test
    public void testAuth() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        assertThat(capabilitieslist.contains("AUTH LOGIN PLAIN"))
            .as("anouncing auth required")
            .isTrue();
        // is this required or just for compatibility?
        // assertTrue("anouncing auth required",
        // capabilitieslist.contains("AUTH=LOGIN PLAIN"));

        String userName = USER_LOCALHOST;
        String noexistUserName = "noexist_test_user_smtp";
        String sender = USER_LOCALHOST;
        smtpProtocol.sendCommand("AUTH FOO", null);
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error: unrecognized authentication type")
            .isEqualTo(504);

        smtpProtocol.setSender(sender);

        assertThat(smtpProtocol.getReplyCode())
            .as("expected 530 error")
            .isEqualTo(530);

        assertThat(testSystem.usersRepository.contains(Username.of(noexistUserName)))
            .as("user not existing")
            .isFalse();

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + noexistUserName + "\0pwd\0").getBytes(UTF_8)));
        // smtpProtocol.sendCommand(noexistUserName+"pwd".toCharArray());
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error")
            .isEqualTo(535);

        testSystem.usersRepository.addUser(Username.of(userName), "pwd");

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0wrongpwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error")
            .isEqualTo(535);

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0pwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);

        smtpProtocol.setSender(sender);

        smtpProtocol.sendCommand("AUTH PLAIN");
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error: User has previously authenticated.")
            .isEqualTo(503);

        smtpProtocol.addRecipient("mail@sample.com");
        smtpProtocol.sendShortMessageData("From: " + sender + "\r\n\r\nSubject: test\r\n\r\nTest body testAuth\r\n");

        smtpProtocol.quit();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNotNull();
    }

    @Test
    public void testAuthSendMail() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        String userName = USER_LOCALHOST;
        String sender = USER_LOCALHOST;

        testSystem.usersRepository.addUser(Username.of(userName), "pwd");

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0pwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);

        smtpProtocol.setSender(sender);
        smtpProtocol.addRecipient("mail@sample.com");
        smtpProtocol.sendShortMessageData("From: " + sender + "\r\n\r\nSubject: test\r\n\r\nTest body testAuth\r\n");

        smtpProtocol.quit();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNotNull();
    }

    @Test
    public void testAuthShouldSucceedWhenPasswordHasMoreThan255Characters() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", InetAddress.getLocalHost().toString());
        String userName = USER_LOCALHOST;
        String userPassword = "1".repeat(300);
        testSystem.usersRepository.addUser(Username.of(userName), userPassword);

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0" + userPassword + "\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);
    }

    @Test
    public void testAuthShouldFailedWhenUserPassIsNotBase64Decoded() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", InetAddress.getLocalHost().toString());
        String userName = USER_LOCALHOST;
        String userPassword = "1".repeat(300);
        testSystem.usersRepository.addUser(Username.of(userName), userPassword);

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand("canNotDecode");
        assertThat(smtpProtocol.getReplyString())
            .contains("535 Authentication Failed");
    }

    @Test
    public void testAuthSendMailFromAlias() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        String userName = USER_LOCAL_DOMAIN;
        String sender = "alias_test_user_smtp@example.local";

        testSystem.usersRepository.addUser(Username.of(userName), "pwd");
        testSystem.rewriteTable.addAliasMapping(MappingSource.fromUser(Username.of(sender)), userName);

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0pwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);

        smtpProtocol.setSender(sender);
        smtpProtocol.addRecipient("mail@sample.com");
        smtpProtocol.sendShortMessageData("From: " + sender + "\r\n\r\nSubject: test\r\n\r\nTest body testAuth\r\n");

        smtpProtocol.quit();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNotNull();
    }


    // CF JAMES-4040
    @Test
    public void shouldBeCompatibleWithEMClient() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "[IPv6:::ffff:172.16.149.220]");
        assertThat(smtpProtocol.getReplyString()).contains("250");
    }

    @Test
    void ehloShouldAcceptAlphanumericHostname() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "7kO2OrE");
        assertThat(smtpProtocol.getReplyString()).contains("250");
    }

    @Test
    public void testAuthSendMailFromDomainAlias() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        String userName = USER_LOCAL_DOMAIN;
        String sender = "test_user_smtp@examplebis.local";

        testSystem.usersRepository.addUser(Username.of(userName), "pwd");
        testSystem.rewriteTable.addDomainAliasMapping(MappingSource.fromDomain(Domain.of("examplebis.local")), Domain.of(LOCAL_DOMAIN));

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0pwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);

        smtpProtocol.setSender(sender);
        smtpProtocol.addRecipient("mail@sample.com");
        smtpProtocol.sendShortMessageData("From: " + sender + "\r\n\r\nSubject: test\r\n\r\nTest body testAuth\r\n");

        smtpProtocol.quit();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNotNull();
    }

    @Test
    public void testAuthSendMailFromGroupAlias() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", InetAddress.getLocalHost().toString());
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        String userName = USER_LOCAL_DOMAIN;
        String sender = "group@example.local";

        testSystem.usersRepository.addUser(Username.of(userName), "pwd");
        testSystem.rewriteTable.addGroupMapping(MappingSource.fromUser(Username.of(sender)), userName);

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0pwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);

        smtpProtocol.setSender(sender);
        smtpProtocol.addRecipient("mail@sample.com");
        smtpProtocol.sendShortMessageData("Subject: test\r\n\r\nTest body testAuth\r\n");

        assertThat(smtpProtocol.getReplyCode())
            .as("expected error")
            .isEqualTo(503);

        smtpProtocol.quit();

        // mail was not propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNull();
    }

    @Test
    public void testAuthWithEmptySender() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        smtpConfiguration.setAuthorizingAnnounce();
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo " + InetAddress.getLocalHost());

        String userName = USER_LOCALHOST;
        testSystem.usersRepository.addUser(Username.of(userName), "pwd");

        smtpProtocol.setSender("");

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0pwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);

        smtpProtocol.addRecipient("mail@sample.com");
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error")
            .isEqualTo(503);

        smtpProtocol.quit();
    }

    @Test
    public void testNoRecepientSpecified() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo " + InetAddress.getLocalHost());

        smtpProtocol.setSender("mail@sample.com");

        // left out for test smtpProtocol.rcpt(new Address("mail@localhost"));

        smtpProtocol.sendShortMessageData("Subject: test\r\n\r\nTest body testNoRecepientSpecified\r\n");
        assertThat(SMTPReply.isNegativePermanent(smtpProtocol.getReplyCode()))
            .as("sending succeeded without recepient")
            .isTrue();

        smtpProtocol.quit();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();
    }

    @Test
    public void testMultipleMailsAndRset() throws Exception {
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo " + InetAddress.getLocalHost());

        smtpProtocol.setSender("mail@sample.com");

        smtpProtocol.reset();

        smtpProtocol.setSender("mail@sample.com");

        smtpProtocol.quit();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("no mail received by mail server")
            .isNull();
    }

    @Test
    public void testRelayingDenied() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("128.0.0.1/8");
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo localhost");

        smtpProtocol.setSender("mail@sample.com");

        smtpProtocol.addRecipient("maila@sample.com");
        assertThat(smtpProtocol.getReplyCode())
            .as("expected 550 error")
            .isEqualTo(550);
    }

    @Test
    public void testHandleAnnouncedMessageSizeLimitExceeded() throws Exception {
        smtpConfiguration.setMaxMessageSize(1); // set message limit to 1kb
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo localhost");

        smtpProtocol.sendCommand("MAIL FROM:<mail@localhost> SIZE=1025", null);
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error: max msg size exceeded")
            .isEqualTo(552);

        smtpProtocol.addRecipient("mail@localhost");
        assertThat(smtpProtocol.getReplyCode())
            .as("expected error")
            .isEqualTo(503);
    }

    @Test
    public void testHandleMessageSizeLimitExceeded() throws Exception {
        smtpConfiguration.setMaxMessageSize(1); // set message limit to 1kb
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo localhost");

        smtpProtocol.setSender("mail@localhost");
        smtpProtocol.addRecipient("mail@localhost");

        Writer wr = smtpProtocol.sendMessageData();
        // create Body with more than 1kb . 502
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write(
                "1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100\r\n");
        // second line
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("123456781012345678201\r\n"); // 521 + CRLF = 523 + 502 => 1025
        wr.close();

        assertThat(smtpProtocol.completePendingCommand())
            .isFalse();

        assertThat(smtpProtocol.getReplyCode())
            .as("expected 552 error")
            .isEqualTo(552);

    }

    @Test
    public void testHandleMessageSizeLimitRespected() throws Exception {
        smtpConfiguration.setMaxMessageSize(1); // set message limit to 1kb
        init(smtpConfiguration);

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo localhost");

        smtpProtocol.setSender("mail@localhost");
        smtpProtocol.addRecipient("mail@localhost");

        Writer wr = smtpProtocol.sendMessageData();
        // create Body with less than 1kb
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012345678301234567840123456785012345678601234567870123456788012345678901234567100");
        wr.write("1234567810123456782012\r\n"); // 1022 + CRLF = 1024
        wr.close();

        assertThat(smtpProtocol.completePendingCommand())
            .isTrue();

        assertThat(smtpProtocol.getReplyCode())
            .as("expected 250 ok")
            .isEqualTo(250);

    }
    // Check if auth users get not rejected cause rbl. See JAMES-566

    @Test
    public void testDNSRBLNotRejectAuthUser() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("192.168.0.1/32");
        smtpConfiguration.setAuthorizingAnnounce();
        smtpConfiguration.useRBL(true);
        init(smtpConfiguration);

        testSystem.dnsServer.setLocalhostByName(InetAddress.getByName("127.0.0.1"));

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");
        String[] capabilityRes = smtpProtocol.getReplyStrings();

        List<String> capabilitieslist = new ArrayList<>();
        for (int i = 1; i < capabilityRes.length; i++) {
            capabilitieslist.add(capabilityRes[i].substring(4));
        }

        assertThat(capabilitieslist.contains("AUTH LOGIN PLAIN"))
            .as("anouncing auth required")
            .isTrue();
        // is this required or just for compatibility? assertTrue("anouncing
        // auth required", capabilitieslist.contains("AUTH=LOGIN PLAIN"));

        String userName = USER_LOCALHOST;
        String sender = USER_LOCALHOST;

        //smtpProtocol.setSender(sender);

        testSystem.usersRepository.addUser(Username.of(userName), "pwd");

        smtpProtocol.sendCommand("AUTH PLAIN");
        smtpProtocol.sendCommand(Base64.getEncoder().encodeToString(("\0" + userName + "\0pwd\0").getBytes(UTF_8)));
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated")
            .isEqualTo(235);

        smtpProtocol.setSender(sender);
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated.. not reject")
            .isEqualTo(250);

        smtpProtocol.addRecipient("mail@sample.com");
        assertThat(smtpProtocol.getReplyCode())
            .as("authenticated.. not reject")
            .isEqualTo(250);

        smtpProtocol.sendShortMessageData("From: " + sender + "\r\n\r\nSubject: test\r\n\r\nTest body testDNSRBLNotRejectAuthUser\r\n");

        smtpProtocol.quit();

        // mail was propagated by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail received by mail server")
            .isNotNull();
    }

    @Test
    public void testDNSRBLRejectWorks() throws Exception {
        smtpConfiguration.setAuthorizedAddresses("192.168.0.1/32");
        smtpConfiguration.useRBL(true);
        init(smtpConfiguration);

        testSystem.dnsServer.setLocalhostByName(InetAddress.getByName("127.0.0.1"));

        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");

        String sender = USER_LOCALHOST;

        smtpProtocol.setSender(sender);
        assertThat(smtpProtocol.getReplyCode())
            .as("reject")
            .isEqualTo(554);

        smtpProtocol.sendShortMessageData("Subject: test\r\n\r\nTest body testDNSRBLRejectWorks\r\n");

        smtpProtocol.quit();

        // mail was rejected by SMTPServer
        assertThat(testSystem.queue.getLastMail())
            .as("mail reject by mail server")
            .isNull();
    }

    @Test
    public void testAddressBracketsEnforcementDisabled() throws Exception {
        smtpConfiguration.setAddressBracketsEnforcement(false);
        init(smtpConfiguration);
        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");

        smtpProtocol.sendCommand("mail from:", "test@localhost");
        assertThat(smtpProtocol.getReplyCode())
            .as("accept")
            .isEqualTo(250);

        smtpProtocol.sendCommand("rcpt to:", "mail@sample.com");
        assertThat(smtpProtocol.getReplyCode())
            .as("accept")
            .isEqualTo(250);

        smtpProtocol.quit();

        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");

        smtpProtocol.sendCommand("mail from:", "<test@localhost>");
        assertThat(smtpProtocol.getReplyCode())
            .as("accept")
            .isEqualTo(250);

        smtpProtocol.sendCommand("rcpt to:", "<mail@sample.com>");
        assertThat(smtpProtocol.getReplyCode())
            .as("accept")
            .isEqualTo(250);

        smtpProtocol.quit();
    }

    @Test
    public void testAddressBracketsEnforcementEnabled() throws Exception {
        init(smtpConfiguration);
        SMTPClient smtpProtocol = new SMTPClient();
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        smtpProtocol.connect(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        smtpProtocol.sendCommand("ehlo", "localhost");

        smtpProtocol.sendCommand("mail from:", "test@localhost");
        assertThat(smtpProtocol.getReplyCode())
            .as("reject")
            .isEqualTo(501);
        smtpProtocol.sendCommand("mail from:", "<test@localhost>");
        assertThat(smtpProtocol.getReplyCode())
            .as("accept")
            .isEqualTo(250);

        smtpProtocol.sendCommand("rcpt to:", "mail@sample.com");
        assertThat(smtpProtocol.getReplyCode())
            .as("reject")
            .isEqualTo(501);
        smtpProtocol.sendCommand("rcpt to:", "<mail@sample.com>");
        assertThat(smtpProtocol.getReplyCode())
            .as("accept")
            .isEqualTo(250);

        smtpProtocol.quit();
    }

    // See http://www.ietf.org/rfc/rfc2920.txt 4: Examples
    @Test
    public void testPipelining() throws Exception {
        StringBuilder buf = new StringBuilder();
        init(smtpConfiguration);
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        Socket client = new Socket(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        buf.append("HELO TEST");
        buf.append("\r\n");
        buf.append("MAIL FROM: <test@localhost>");
        buf.append("\r\n");
        buf.append("RCPT TO: <test2@localhost>");
        buf.append("\r\n");
        buf.append("DATA");
        buf.append("\r\n");
        buf.append("Subject: test");
        buf.append("\r\n");

        buf.append("\r\n");
        buf.append("content");
        buf.append("\r\n");
        buf.append(".");
        buf.append("\r\n");
        buf.append("quit");
        buf.append("\r\n");

        OutputStream out = client.getOutputStream();

        out.write(buf.toString().getBytes());
        out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("Connection made")
            .isEqualTo(220);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("HELO accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("MAIL FROM accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("RCPT TO accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("DATA accepted")
            .isEqualTo(354);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("Message accepted")
            .isEqualTo(250);
        in.close();
        out.close();
        client.close();
    }

    // See http://www.ietf.org/rfc/rfc2920.txt 4: Examples
    @Test
    public void testRejectAllRCPTPipelining() throws Exception {
        StringBuilder buf = new StringBuilder();
        smtpConfiguration.setAuthorizedAddresses("");
        init(smtpConfiguration);
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        Socket client = new Socket(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        buf.append("HELO TEST");
        buf.append("\r\n");
        buf.append("MAIL FROM: <test@localhost>");
        buf.append("\r\n");
        buf.append("RCPT TO: <test@invalid>");
        buf.append("\r\n");
        buf.append("RCPT TO: <test2@invalid>");
        buf.append("\r\n");
        buf.append("DATA");
        buf.append("\r\n");
        buf.append("Subject: test");
        buf.append("\r\n");

        buf.append("\r\n");
        buf.append("content");
        buf.append("\r\n");
        buf.append(".");
        buf.append("\r\n");
        buf.append("quit");
        buf.append("\r\n");

        OutputStream out = client.getOutputStream();

        out.write(buf.toString().getBytes());
        out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("Connection made")
            .isEqualTo(220);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("HELO accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("MAIL FROM accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("RCPT TO rejected")
            .isEqualTo(550);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("RCPT TO rejected")
            .isEqualTo(550);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("DATA not accepted")
            .isEqualTo(503);
        in.close();
        out.close();
        client.close();
    }

    @Test
    public void testRejectOneRCPTPipelining() throws Exception {
        StringBuilder buf = new StringBuilder();
        smtpConfiguration.setAuthorizedAddresses("");
        init(smtpConfiguration);
        InetSocketAddress bindedAddress = testSystem.getBindedAddress();
        Socket client = new Socket(bindedAddress.getAddress().getHostAddress(), bindedAddress.getPort());

        buf.append("HELO TEST");
        buf.append("\r\n");
        buf.append("MAIL FROM: <test@localhost>");
        buf.append("\r\n");
        buf.append("RCPT TO: <test@invalid>");
        buf.append("\r\n");
        buf.append("RCPT TO: <test2@localhost>");
        buf.append("\r\n");
        buf.append("DATA");
        buf.append("\r\n");
        buf.append("Subject: test");
        buf.append("\r\n");

        buf.append("\r\n");
        buf.append("content");
        buf.append("\r\n");
        buf.append(".");
        buf.append("\r\n");
        buf.append("quit");
        buf.append("\r\n");

        OutputStream out = client.getOutputStream();

        out.write(buf.toString().getBytes());
        out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("Connection made")
            .isEqualTo(220);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("HELO accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("MAIL FROM accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("RCPT TO rejected")
            .isEqualTo(550);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("RCPT accepted")
            .isEqualTo(250);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("DATA accepted")
            .isEqualTo(354);
        assertThat(Integer.parseInt(in.readLine().split(" ")[0]))
            .as("Message accepted")
            .isEqualTo(250);
        in.close();
        out.close();
        client.close();
    }
}
