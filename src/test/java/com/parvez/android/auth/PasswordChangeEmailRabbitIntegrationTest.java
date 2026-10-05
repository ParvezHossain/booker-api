package com.parvez.android.auth;

import com.parvez.android.saas.WorkspaceAccounts;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"books.requests.email.enabled=false", "app.password-reset.email.enabled=false",
        "app.password-change.email.enabled=true", "app.password-change.email.poll-millis=3600000",
        "app.password-change.email.listener-auto-startup=false", "app.password-change.email.queue-limit=10",
        "app.password-change.email.retry-seconds=1", "app.password-change.email.max-attempts=2",
        "app.password-change.email.from=booker@example.com", "management.health.mail.enabled=false",
        "spring.autoconfigure.exclude=org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration,org.jobrunr.spring.autoconfigure.storage.JobRunrSqlStorageAutoConfiguration"})
class PasswordChangeEmailRabbitIntegrationTest {
    static final String schema = "change_rabbit_" + UUID.randomUUID().toString().replace("-", "");
    static final String queue = "booker.password-change-emails.tests." + UUID.randomUUID();
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("DATABASE_URL") + "?currentSchema=" + schema);
        properties.add("spring.flyway.schemas", () -> schema);
        properties.add("spring.flyway.default-schema", () -> schema);
        properties.add("app.password-change.email.queue", () -> queue);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceAccounts accounts;
    @Autowired PasswordService passwords;
    @Autowired TokenService tokens;
    @Autowired PasswordChangeEmailPublisher publisher;
    @Autowired PasswordChangeEmailWorker worker;
    @Autowired PasswordChangeEmailConsumer consumer;
    @Autowired TransactionTemplate transaction;
    @Autowired PasswordChangeEmailSettings settings;
    @Autowired RabbitTemplate rabbit;
    @Autowired SimpleRabbitListenerContainerFactory passwordChangeEmailListenerFactory;
    @MockitoBean JavaMailSender mail;
    RabbitAdmin admin;
    SimpleMessageListenerContainer first, second;
    String email;
    final String old = "test-password-123";
    final String fresh = "new-password-1234";
    final PasswordChangeContext context = new PasswordChangeContext("203.0.113.7",
            "Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 <script>untrusted</script>");

    @BeforeEach void setup() {
        admin = new RabbitAdmin(rabbit.getConnectionFactory()); admin.initialize();
        admin.purgeQueue(queue); admin.purgeQueue(queue + ".dead");
        jdbc.update("DELETE FROM password_change_history");
        email = "changed-" + UUID.randomUUID() + "@example.com";
        com.parvez.android.TestAccounts.registerVerified(accounts, jdbc, new WorkspaceAccounts.Signup("Changed workspace", email, old));
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }
    @AfterEach void cleanup() {
        if (first != null) first.stop(); if (second != null) second.stop();
    }
    @AfterAll static void cleanupSchema(@Autowired JdbcTemplate jdbc, @Autowired RabbitTemplate rabbit) {
        var admin = new RabbitAdmin(rabbit.getConnectionFactory());
        admin.deleteQueue(queue); admin.deleteQueue(queue + ".dead");
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
    UUID change() {
        passwords.change(email, old, fresh, context);
        return jdbc.queryForObject("SELECT id FROM password_change_history WHERE email=?", UUID.class, email);
    }
    int remaining() { return jdbc.queryForObject("SELECT count(*) FROM password_change_emails", Integer.class); }
    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(50); }
        fail("Timed out waiting for password change email state");
    }
    SimpleMessageListenerContainer startConsumer() {
        var container = passwordChangeEmailListenerFactory.createListenerContainer();
        container.setQueueNames(queue);
        container.setMessageListener((org.springframework.amqp.core.MessageListener) consumer::receive);
        container.start(); return container;
    }
    void send(byte[] body) throws Exception {
        var correlation = new CorrelationData();
        rabbit.send("", queue, new Message(body, new MessageProperties()), correlation);
        assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).ack());
        assertNull(correlation.getReturned());
    }
    @Test void acceptanceIsAtomicAndBrokerCarriesOnlyPersistentOpaqueId() {
        UUID id = change();
        verifyNoInteractions(mail);
        assertNotNull(tokens.login(email, fresh));
        publisher.processPending();
        var message = rabbit.receive(queue, 5000);
        assertNotNull(message);
        assertEquals(id.toString(), new String(message.getBody(), StandardCharsets.US_ASCII));
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getReceivedDeliveryMode());
        assertNotNull(jdbc.queryForMap("SELECT published_at FROM password_change_emails WHERE id=?", id).get("published_at"));
    }
    @Test void deliveryEscapesMetadataTargetsAffectedAccountAndRetainsAudit() throws Exception {
        UUID id = change();
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var message = (MimeMessage) invocation.getArgument(0);
            assertEquals(email, message.getAllRecipients()[0].toString());
            assertEquals(1, message.getAllRecipients().length);
            assertEquals("Your Booker password has changed", message.getSubject());
            var mixed = (Multipart) message.getContent();
            var alternative = (Multipart) mixed.getBodyPart(0).getContent();
            String plain = (String) alternative.getBodyPart(0).getContent();
            String html = (String) alternative.getBodyPart(1).getContent();
            assertTrue(plain.contains("203.0.113.7"));
            assertTrue(plain.contains("Chrome"));
            assertTrue(plain.contains("Computer / Windows"));
            assertTrue(html.contains("&lt;script&gt;untrusted&lt;/script&gt;"));
            assertFalse(html.contains("<script>"));
            assertFalse(plain.contains(old)); assertFalse(plain.contains(fresh));
            return null;
        }).when(mail).send(any(MimeMessage.class));
        worker.deliver(id);
        assertEquals(0, remaining());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM password_change_history WHERE id=?", Integer.class, id));
        worker.deliver(id);
        verify(mail, times(1)).send(any(MimeMessage.class));
    }
    @Test void smtpFailureDelaysRetryThenParksWithoutLosingHistoryOrPassword() {
        UUID id = change();
        doThrow(new MailSendException("test-only provider failure")).when(mail).send(any(MimeMessage.class));
        worker.deliver(id);
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM password_change_emails WHERE id=?", Integer.class, id));
        assertTrue(jdbc.queryForObject("SELECT available_at > clock_timestamp() FROM password_change_emails WHERE id=?", Boolean.class, id));
        worker.deliver(id);
        verify(mail, times(1)).send(any(MimeMessage.class));
        jdbc.update("UPDATE password_change_emails SET available_at=clock_timestamp() WHERE id=?", id);
        worker.deliver(id);
        assertNotNull(jdbc.queryForMap("SELECT failed_at FROM password_change_emails WHERE id=?", id).get("failed_at"));
        worker.deliver(id);
        verify(mail, times(2)).send(any(MimeMessage.class));
        assertNotNull(tokens.login(email, fresh));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM password_change_history WHERE id=?", Integer.class, id));
    }
    @Test void slowSmtpDoesNotBlockAnotherPasswordChangeAndOldLeaseCannotFinalize() throws Exception {
        UUID id = change();
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            sending.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return null;
        }).when(mail).send(any(MimeMessage.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var delivery = pool.submit(() -> worker.deliver(id));
            try {
                assertTrue(sending.await(5, TimeUnit.SECONDS));
                pool.submit(() -> passwords.change(email, fresh, "third-password-123", PasswordChangeContext.unknown()))
                        .get(5, TimeUnit.SECONDS);
                UUID newLease = UUID.randomUUID();
                jdbc.update("UPDATE password_change_emails SET lease_id=?, lease_until=clock_timestamp()+interval '60 seconds' WHERE id=?", newLease, id);
                release.countDown(); delivery.get(5, TimeUnit.SECONDS);
                assertEquals(newLease, jdbc.queryForObject("SELECT lease_id FROM password_change_emails WHERE id=?", UUID.class, id));
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM password_change_history WHERE email=?", Integer.class, email));
            } finally { release.countDown(); }
        }
    }
    @Test void expiredLeaseAndLostBrokerMessageRecoverViaRedispatch() {
        UUID id = change();
        jdbc.update("UPDATE password_change_emails SET published_at=clock_timestamp()-interval '301 seconds', lease_id=?, lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
                UUID.randomUUID(), id);
        publisher.processPending();
        assertNotNull(rabbit.receive(queue, 5000));
        worker.deliver(id);
        assertEquals(0, remaining());
    }
    @Test void absentSmtpDefersReceiptWithoutConsumingAttemptsThenConfigurationCanRecover() {
        UUID id = change();
        var unavailable = mock(SmtpPasswordChangeDelivery.class);
        new PasswordChangeEmailWorker(jdbc, transaction, unavailable, settings).deliver(id);
        var receipt = jdbc.queryForMap("SELECT attempts, lease_id, available_at > clock_timestamp() AS delayed FROM password_change_emails WHERE id=?", id);
        assertEquals(0, receipt.get("attempts"));
        assertNull(receipt.get("lease_id"));
        assertEquals(true, receipt.get("delayed"));
        verify(unavailable, never()).send(anyString(), any(), anyString(), any(), anyString(), anyString(), any());
        jdbc.update("UPDATE password_change_emails SET available_at=clock_timestamp() WHERE id=?", id);
        worker.deliver(id);
        assertEquals(0, remaining());
    }
    @Test void saturatedQueueLeavesUnconfirmedReceiptPendingForLaterPublication() throws Exception {
        // Quorum queue limits can overshoot during in-flight publication. Observe
        // an actual negative confirm instead of assuming an exact ready count.
        boolean rejected = false;
        for (int i = 0; i < 100; i++) {
            var correlation = new CorrelationData();
            rabbit.send("", queue, new Message(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII),
                    new MessageProperties()), correlation);
            if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack()) { rejected = true; break; }
        }
        assertTrue(rejected);
        UUID id = change();
        publisher.processPending();
        assertNull(jdbc.queryForMap("SELECT published_at FROM password_change_emails WHERE id=?", id).get("published_at"));
        assertEquals(1, remaining());
        verifyNoInteractions(mail);
        while (rabbit.receive(queue, 100) != null) { /* Drain only synthetic receipt IDs. */ }
        first = startConsumer();
        publisher.processPending();
        await(() -> remaining() == 0);
    }
    @Test void malformedMessagesDeadLetterAndTwoConsumersDeliverOnce() throws Exception {
        UUID id = change();
        send("not-an-id".getBytes(StandardCharsets.US_ASCII));
        first = startConsumer(); second = startConsumer();
        await(() -> admin.getQueueInfo(queue + ".dead").getMessageCount() == 1);
        publisher.processPending();
        await(() -> remaining() == 0);
        send(id.toString().getBytes(StandardCharsets.US_ASCII));
        await(() -> admin.getQueueInfo(queue).getMessageCount() == 0);
        verify(mail, times(1)).send(any(MimeMessage.class));
    }
    @Test void outboxPersistenceFailureRollsBackPasswordAuditAndSessions() {
        var session = tokens.login(email, old);
        String resetToken = "R".repeat(43);
        String digest = com.parvez.android.security.OpaqueTokens.sha256Hex(resetToken);
        jdbc.update("INSERT INTO password_reset_tokens(email,token_hash,expires_at) VALUES (?,?,clock_timestamp()+interval '30 minutes')", email, digest);
        jdbc.execute("""
                CREATE FUNCTION reject_change_email() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test-only receipt failure'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER reject_change_email BEFORE INSERT ON password_change_emails FOR EACH ROW EXECUTE FUNCTION reject_change_email()");
        try {
            assertThrows(RuntimeException.class, this::change);
            assertThrows(RuntimeException.class, () -> passwords.reset(resetToken, fresh, context));
            assertEquals(0, remaining());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_change_history WHERE email=?", Integer.class, email));
            assertNotNull(tokens.decodeAccess(session.accessToken()));
            assertNotNull(tokens.refresh(session.refreshToken()));
            assertNotNull(tokens.login(email, old));
            assertEquals(digest, jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE email=?", String.class, email));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_reset_history WHERE email=?", Integer.class, email));
        } finally {
            jdbc.execute("DROP TRIGGER reject_change_email ON password_change_emails");
            jdbc.execute("DROP FUNCTION reject_change_email()");
        }
    }
}
