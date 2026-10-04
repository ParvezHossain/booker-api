package com.parvez.android.library;

import com.parvez.android.saas.WorkspaceAccounts;
import com.parvez.android.saas.WorkspacePrincipal;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"books.requests.email.enabled=true", "books.requests.email-poll-millis=3600000",
        "books.requests.email.listener-auto-startup=false", "books.requests.email.queue-limit=10",
        "books.requests.email.retry-seconds=1", "books.requests.email.max-attempts=2",
        "app.password-reset.from=booker@example.com", "management.health.mail.enabled=false",
        "spring.autoconfigure.exclude=org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration,org.jobrunr.spring.autoconfigure.storage.JobRunrSqlStorageAutoConfiguration"})
class RequestEmailRabbitIntegrationTest {
    static final String schema="email_rabbit_"+UUID.randomUUID().toString().replace("-", "");
    static final String queue="booker.request-emails.tests."+UUID.randomUUID();
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("DATABASE_URL") + "?currentSchema=" + schema);
        properties.add("spring.flyway.schemas", () -> schema);
        properties.add("spring.flyway.default-schema", () -> schema);
        properties.add("books.requests.email.queue", () -> queue);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired WorkspaceAccounts accounts;
    @Autowired PublicLibraryRequestService requests;
    @Autowired RequestEmailPublisher publisher;
    @Autowired RequestEmailConsumer consumer;
    @Autowired RabbitTemplate rabbit;
    @Autowired SimpleRabbitListenerContainerFactory requestEmailListenerFactory;
    @MockitoBean JavaMailSender mail;
    SimpleMessageListenerContainer first, second;
    RabbitAdmin admin;

    @BeforeEach void setup() {
        admin=new RabbitAdmin(rabbit.getConnectionFactory());admin.initialize();
        admin.purgeQueue(queue);admin.purgeQueue(queue+".dead");
        jdbc.update("DELETE FROM public_request_emails");
        String suffix=UUID.randomUUID().toString();
        accounts.register(new WorkspaceAccounts.Signup("Queue workspace", "reader-"+suffix+"@example.com", "test-password-123"));
        accounts.provisionSuperAdmin("admin@example.com", "test-password-123");
        var owner=(WorkspacePrincipal)accounts.loadUserByUsername("reader-"+suffix+"@example.com");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(owner,owner.getPassword(),owner.getAuthorities()));
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }
    SimpleMessageListenerContainer startConsumer() {
        var container=requestEmailListenerFactory.createListenerContainer();
        container.setQueueNames(queue);
        container.setMessageListener((org.springframework.amqp.core.MessageListener)consumer::receive);
        container.start();return container;
    }
    @AfterEach void cleanup() {
        if(first!=null)first.stop();if(second!=null)second.stop();
        SecurityContextHolder.clearContext();
    }
    @AfterAll static void cleanupSchema(@Autowired JdbcTemplate jdbc, @Autowired RabbitTemplate rabbit) {
        var admin=new RabbitAdmin(rabbit.getConnectionFactory());admin.deleteQueue(queue);admin.deleteQueue(queue+".dead");
        jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
    }
    UUID submit() {
        var request=requests.submit(new PublicLibraryRequestService.Submit("Queued "+UUID.randomUUID(),"Author"));
        return jdbc.queryForObject("SELECT id FROM public_request_emails WHERE request_id=?",UUID.class,request.id());
    }
    void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end) { if(condition.getAsBoolean())return;Thread.sleep(50); }
        fail("Timed out waiting for durable email queue state");
    }
    int remaining() { return jdbc.queryForObject("SELECT count(*) FROM public_request_emails",Integer.class); }
    void sendToken(String target, byte[] body) throws Exception {
        var correlation=new CorrelationData();
        var properties=new MessageProperties();properties.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        rabbit.send("",target,new Message(body,properties),correlation);
        assertTrue(correlation.getFuture().get(5,TimeUnit.SECONDS).ack());assertNull(correlation.getReturned());
    }
    @Test void peakBacklogUsesOneActiveConsumerAcrossTwoInstances() throws Exception {
        assertTrue(context.containsBean("applicationTaskExecutor"), "Dedicated schedulers must preserve Spring's application executor");
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger(),sent=new AtomicInteger();
        doAnswer(invocation -> {
            int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);
            try { Thread.sleep(25);sent.incrementAndGet(); } finally { active.decrementAndGet(); }
            return null;
        }).when(mail).send(any(MimeMessage.class));
        first=startConsumer();second=startConsumer();
        await(() -> first.getActiveConsumerCount()==1 && second.getActiveConsumerCount()==1);
        for(int i=0;i<25;i++) {
            // A peak can span workspaces; each workspace keeps its monthly quota.
            if(i==10 || i==20) {
                String email="peak-"+UUID.randomUUID()+"@example.com";
                accounts.register(new WorkspaceAccounts.Signup("Peak workspace",email,"test-password-123"));
                var owner=(WorkspacePrincipal)accounts.loadUserByUsername(email);
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(owner,owner.getPassword(),owner.getAuthorities()));
            }
            submit();
        }
        await(() -> { publisher.processPending();return remaining()==0; });
        assertEquals(25,sent.get());assertEquals(1,maximum.get());
    }
    @Test void fullBrokerQueueDoesNotDiscardDatabaseReceipt() throws Exception {
        boolean rejected=false;
        for(int i=0;i<100;i++) {
            var correlation=new CorrelationData();
            rabbit.send("",queue,new Message(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII),new MessageProperties()),correlation);
            if(!correlation.getFuture().get(5,TimeUnit.SECONDS).ack()) { rejected=true;break; }
        }
        assertTrue(rejected,"Quorum queue must apply publisher backpressure");
        UUID id=submit();publisher.processPending();
        assertNull(jdbc.queryForObject("SELECT published_at FROM public_request_emails WHERE id=?",java.sql.Timestamp.class,id));
        while(rabbit.receive(queue,100)!=null) { /* Drain the synthetic backlog. */ }
        first=startConsumer();publisher.processPending();await(() -> remaining()==0);
        verify(mail,times(1)).send(any(MimeMessage.class));
    }
    @Test void smtpFailureIsDelayedAndDoesNotBlockLaterMail() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        doAnswer(invocation -> { if(calls.incrementAndGet()==1)throw new MailSendException("offline");return null; }).when(mail).send(any(MimeMessage.class));
        first=startConsumer();UUID failed=submit();publisher.processPending();
        await(() -> jdbc.queryForObject("SELECT attempts FROM public_request_emails WHERE id=?",Integer.class,failed)==1);
        assertNull(jdbc.queryForObject("SELECT published_at FROM public_request_emails WHERE id=?",java.sql.Timestamp.class,failed));
        jdbc.update("UPDATE public_request_emails SET available_at=now()+interval '1 hour' WHERE id=?",failed);
        submit();publisher.processPending();await(() -> remaining()==1);
        jdbc.update("UPDATE public_request_emails SET available_at=now() WHERE id=?",failed);
        publisher.processPending();await(() -> remaining()==0);assertEquals(3,calls.get());
    }
    @Test void exhaustedRetriesParkReceiptInsteadOfLooping() throws Exception {
        doThrow(new MailSendException("offline")).when(mail).send(any(MimeMessage.class));
        first=startConsumer();UUID id=submit();publisher.processPending();
        await(() -> jdbc.queryForObject("SELECT attempts FROM public_request_emails WHERE id=?",Integer.class,id)==1);
        jdbc.update("UPDATE public_request_emails SET available_at=now() WHERE id=?",id);
        publisher.processPending();await(() -> jdbc.queryForObject("SELECT failed_at IS NOT NULL FROM public_request_emails WHERE id=?",Boolean.class,id));
        publisher.processPending();assertEquals(2,jdbc.queryForObject("SELECT attempts FROM public_request_emails WHERE id=?",Integer.class,id));
        verify(mail,times(2)).send(any(MimeMessage.class));
    }
    @Test void redeliveryOfCompletedReceiptDoesNotSendAgain() throws Exception {
        first=startConsumer();UUID id=submit();publisher.processPending();await(() -> remaining()==0);
        sendToken(queue,id.toString().getBytes(StandardCharsets.US_ASCII));sendToken(queue,id.toString().getBytes(StandardCharsets.US_ASCII));
        await(() -> ((Number)admin.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue()==0);
        first.stop();verify(mail,times(1)).send(any(MimeMessage.class));
    }
    @Test void malformedTokenIsQuarantinedWithoutSmtp() throws Exception {
        first=startConsumer();sendToken(queue,"invalid".getBytes(StandardCharsets.US_ASCII));
        await(() -> ((Number)admin.getQueueProperties(queue+".dead").get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue()==1);
        assertNotNull(rabbit.receive(queue+".dead",1000));verify(mail,never()).send(any(MimeMessage.class));
    }
    @Test void stalePublishedReceiptIsRedispatchedAfterRecoveryWindow() throws Exception {
        UUID id=submit();jdbc.update("UPDATE public_request_emails SET published_at=now()-interval '10 minutes' WHERE id=?",id);
        first=startConsumer();publisher.processPending();await(() -> remaining()==0);verify(mail,times(1)).send(any(MimeMessage.class));
    }
}
