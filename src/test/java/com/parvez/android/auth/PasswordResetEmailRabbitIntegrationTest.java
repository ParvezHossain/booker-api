package com.parvez.android.auth;

import com.parvez.android.saas.WorkspaceAccounts;
import com.parvez.android.security.OpaqueTokens;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"books.requests.email.enabled=false", "app.password-reset.email.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4",
        "app.password-reset.email.poll-millis=3600000", "app.password-reset.email.listener-auto-startup=false",
        "app.password-reset.email.queue-limit=10", "app.password-reset.email.retry-seconds=1",
        "app.password-reset.email.max-attempts=2", "app.password-reset.from=booker@example.com",
        "app.password-reset.email.encryption-key=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "management.health.mail.enabled=false",
        "spring.autoconfigure.exclude=org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration,org.jobrunr.spring.autoconfigure.storage.JobRunrSqlStorageAutoConfiguration"})
class PasswordResetEmailRabbitIntegrationTest {
    static final String schema = "reset_rabbit_" + UUID.randomUUID().toString().replace("-", "");
    static final String queue = "booker.password-reset-emails.tests." + UUID.randomUUID();
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("DATABASE_URL") + "?currentSchema=" + schema);
        properties.add("spring.flyway.schemas", () -> schema);
        properties.add("spring.flyway.default-schema", () -> schema);
        properties.add("app.password-reset.email.queue", () -> queue);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceAccounts accounts;
    @Autowired PasswordService passwords;
    @Autowired PasswordResetEmailPublisher publisher;
    @Autowired PasswordResetEmailConsumer consumer;
    @Autowired PasswordResetEmailCipher cipher;
    @Autowired RabbitTemplate rabbit;
    @Autowired SimpleRabbitListenerContainerFactory passwordResetEmailListenerFactory;
    @MockitoBean JavaMailSender mail;
    SimpleMessageListenerContainer first, second;
    RabbitAdmin admin;
    String email;

    @BeforeEach void setup() {
        admin = new RabbitAdmin(rabbit.getConnectionFactory()); admin.initialize();
        admin.purgeQueue(queue); admin.purgeQueue(queue + ".dead");
        jdbc.update("DELETE FROM password_reset_emails"); jdbc.update("DELETE FROM password_reset_tokens");
        email = "queued-reset-" + UUID.randomUUID() + "@example.com";
        accounts.register(new WorkspaceAccounts.Signup("Queue workspace", email, "test-password-123"));
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }
    @AfterEach void cleanup() {
        if (first != null) first.stop(); if (second != null) second.stop();
    }
    @AfterAll static void cleanupSchema(@Autowired JdbcTemplate jdbc, @Autowired RabbitTemplate rabbit) {
        var admin = new RabbitAdmin(rabbit.getConnectionFactory()); admin.deleteQueue(queue); admin.deleteQueue(queue + ".dead");
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    UUID request(String account) {
        passwords.forgot(account);
        return jdbc.queryForObject("""
                SELECT e.id FROM password_reset_emails e JOIN password_reset_tokens t
                    ON t.email=e.email AND t.token_hash=e.token_hash WHERE e.email=?
                """, UUID.class, account);
    }
    String token(UUID id) {
        var row = jdbc.queryForMap("SELECT * FROM password_reset_emails WHERE id=?", id);
        return cipher.decrypt((String) row.get("encrypted_token"), id, (String) row.get("email"),
                (String) row.get("token_hash"), ((Timestamp) row.get("expires_at")).toInstant());
    }
    int remaining() { return jdbc.queryForObject("SELECT count(*) FROM password_reset_emails", Integer.class); }
    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(50); }
        fail("Timed out waiting for reset email queue state");
    }
    SimpleMessageListenerContainer startConsumer() {
        var container = passwordResetEmailListenerFactory.createListenerContainer();
        container.setQueueNames(queue); container.setMessageListener((org.springframework.amqp.core.MessageListener) consumer::receive);
        container.start(); return container;
    }
    void sendId(byte[] body) throws Exception {
        var correlation = new CorrelationData();
        rabbit.send("", queue, new Message(body, new MessageProperties()), correlation);
        assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()); assertNull(correlation.getReturned());
    }

    @Test void requestCommitsEncryptedOutboxAndBrokerContainsOnlyPersistentReceiptId() throws Exception {
        UUID id = request(email); String token = token(id);
        verifyNoInteractions(mail);
        assertFalse(jdbc.queryForObject("SELECT encrypted_token FROM password_reset_emails WHERE id=?", String.class, id).contains(token));
        publisher.processPending();
        Message message = rabbit.receive(queue, 1000);
        assertNotNull(message);
        assertEquals(id.toString(), new String(message.getBody(), StandardCharsets.US_ASCII));
        assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getReceivedDeliveryMode());
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM password_reset_emails WHERE id=?", Timestamp.class, id));
        consumer.receive(message);
        assertEquals(0, remaining());
        passwords.reset(token, "new-password-1234");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM password_reset_history WHERE email=?", Integer.class, email));
    }

    @Test void blockedSmtpDoesNotHoldAccountOrReceiptLocksAndOldCompletionCannotDeleteNewMail() throws Exception {
        UUID oldId = request(email);
        String oldToken = token(oldId);
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "SMTP must run outside database transactions");
            sending.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new MailSendException("Test timed out");
            return null;
        }).when(mail).send(any(MimeMessage.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var delivery = pool.submit(() -> consumer.receive(new Message(oldId.toString().getBytes(StandardCharsets.US_ASCII), new MessageProperties())));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            try {
                jdbc.update("UPDATE password_reset_tokens SET requested_at=clock_timestamp()-interval '61 seconds' WHERE email=?", email);
                UUID newer = pool.submit(() -> request(email)).get(3, TimeUnit.SECONDS);
                assertNotEquals(oldId, newer);
                assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> passwords.reset(oldToken, "new-password-1234"));
                release.countDown(); delivery.get(5, TimeUnit.SECONDS);
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM password_reset_emails WHERE id=?", Integer.class, newer));
                assertNotNull(token(newer));
            } finally { release.countDown(); }
        }
    }

    @Test void expiredReplacedAndConsumedTokensAreDiscardedWithoutEmail() {
        UUID old = request(email);
        jdbc.update("UPDATE password_reset_tokens SET requested_at=clock_timestamp()-interval '61 seconds' WHERE email=?", email);
        UUID current = request(email);
        consumer.receive(new Message(old.toString().getBytes(StandardCharsets.US_ASCII), new MessageProperties()));
        assertEquals(1, remaining());
        jdbc.update("UPDATE password_reset_tokens SET expires_at=clock_timestamp()-interval '1 second' WHERE email=?", email);
        publisher.processPending(); assertEquals(0, remaining());
        jdbc.update("UPDATE password_reset_tokens SET requested_at=clock_timestamp()-interval '61 seconds' WHERE email=?", email);
        current = request(email); passwords.reset(token(current), "new-password-1234");
        consumer.receive(new Message(current.toString().getBytes(StandardCharsets.US_ASCII), new MessageProperties()));
        assertEquals(0, remaining()); verifyNoInteractions(mail);
    }

    @Test void delayedFailureAllowsHealthyMailAndRetriesWithOriginalDeadline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> { if (calls.incrementAndGet() == 1) throw new MailSendException("offline"); return null; })
                .when(mail).send(any(MimeMessage.class));
        UUID id = request(email);
        var expiry = jdbc.queryForObject("SELECT expires_at FROM password_reset_tokens WHERE email=?", Timestamp.class, email);
        first = startConsumer(); publisher.processPending();
        await(() -> jdbc.queryForObject("SELECT attempts FROM password_reset_emails WHERE id=?", Integer.class, id) == 1);
        jdbc.update("UPDATE password_reset_emails SET available_at=clock_timestamp()+interval '1 hour' WHERE id=?", id);
        String other = "healthy-" + UUID.randomUUID() + "@example.com";
        accounts.register(new WorkspaceAccounts.Signup("Healthy", other, "test-password-123")); request(other);
        publisher.processPending(); await(() -> remaining() == 1);
        jdbc.update("UPDATE password_reset_emails SET available_at=clock_timestamp() WHERE id=?", id);
        publisher.processPending(); await(() -> remaining() == 0);
        assertEquals(expiry, jdbc.queryForObject("SELECT expires_at FROM password_reset_tokens WHERE email=?", Timestamp.class, email));
        assertEquals(3, calls.get());
    }

    @Test void exhaustedFailureParksThenExpiryRemovesEncryptedPayload() throws Exception {
        doThrow(new MailSendException("offline")).when(mail).send(any(MimeMessage.class));
        UUID id = request(email); first = startConsumer(); publisher.processPending();
        await(() -> jdbc.queryForObject("SELECT attempts FROM password_reset_emails WHERE id=?", Integer.class, id) == 1);
        jdbc.update("UPDATE password_reset_emails SET available_at=clock_timestamp() WHERE id=?", id);
        publisher.processPending();
        await(() -> jdbc.queryForObject("SELECT failed_at IS NOT NULL FROM password_reset_emails WHERE id=?", Boolean.class, id));
        publisher.processPending(); verify(mail, times(2)).send(any(MimeMessage.class));
        jdbc.update("UPDATE password_reset_tokens SET expires_at=clock_timestamp()-interval '1 second' WHERE email=?", email);
        publisher.processPending(); assertEquals(0, remaining());
    }

    @Test void completedReceiptDuplicatesAndMalformedIdsNeverResend() throws Exception {
        UUID id = request(email); first = startConsumer(); publisher.processPending(); await(() -> remaining() == 0);
        sendId(id.toString().getBytes(StandardCharsets.US_ASCII)); sendId("malformed".getBytes(StandardCharsets.US_ASCII));
        await(() -> ((Number) admin.getQueueProperties(queue + ".dead").get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue() == 1);
        first.stop(); verify(mail, times(1)).send(any(MimeMessage.class));
    }

    @Test void abandonedLeaseAndLostPublicationAreRecovered() throws Exception {
        UUID id = request(email);
        jdbc.update("UPDATE password_reset_emails SET lease_id=?, lease_until=clock_timestamp()+interval '1 hour', published_at=clock_timestamp()-interval '10 minutes' WHERE id=?", UUID.randomUUID(), id);
        publisher.processPending(); assertNull(rabbit.receive(queue, 100));
        jdbc.update("UPDATE password_reset_emails SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", id);
        first = startConsumer(); publisher.processPending(); await(() -> remaining() == 0);
        verify(mail, times(1)).send(any(MimeMessage.class));
    }

    @Test void saturatedBrokerLeavesAcceptedRecoveryInDatabase() throws Exception {
        boolean rejected = false;
        for (int i = 0; i < 100; i++) {
            var correlation = new CorrelationData();
            rabbit.send("", queue, new Message(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII), new MessageProperties()), correlation);
            if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack()) { rejected = true; break; }
        }
        assertTrue(rejected);
        UUID id = request(email); publisher.processPending();
        assertNull(jdbc.queryForObject("SELECT published_at FROM password_reset_emails WHERE id=?", Timestamp.class, id));
        verifyNoInteractions(mail);
        while (rabbit.receive(queue, 100) != null) { /* Drain only synthetic receipt IDs. */ }
        first = startConsumer(); publisher.processPending(); await(() -> remaining() == 0);
    }
}
