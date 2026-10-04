package com.parvez.android.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordChangeEmailPublisherTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final TransactionTemplate transaction=mock(TransactionTemplate.class);
    final RabbitTemplate rabbit=mock(RabbitTemplate.class);
    final SmtpPasswordChangeDelivery delivery=mock(SmtpPasswordChangeDelivery.class);
    final UUID id=UUID.randomUUID();
    PasswordChangeEmailPublisher publisher;
    @BeforeEach void setup() {
        when(delivery.isConfigured()).thenReturn(true);
        when(transaction.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        publisher=new PasswordChangeEmailPublisher(jdbc,transaction,rabbit,new PasswordChangeEmailSettings("mail",2,1000,5,30,300,60,1000),delivery);
        when(jdbc.queryForList(anyString(),eq(300))).thenReturn(List.of(Map.of("id",id)));
    }
    void confirms(boolean ack) {
        doAnswer(invocation -> {
            ((CorrelationData)invocation.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(ack,null));return null;
        }).when(rabbit).send(eq(""),eq("mail"),any(Message.class),any(CorrelationData.class));
    }
    @Test void boundedBatchPublishesOnlyPersistentReceiptIdentifiersAfterConfirmation() {
        confirms(true);publisher.processPending();
        verify(rabbit,times(2)).send(eq(""),eq("mail"),argThat(message -> {
            assertEquals(id.toString(),new String(message.getBody(),java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getDeliveryMode());return true;
        }),any(CorrelationData.class));
        verify(jdbc,times(2)).update("UPDATE password_change_emails SET published_at=clock_timestamp() WHERE id=?",id);
    }
    @Test void negativeBrokerConfirmationLeavesReceiptsPendingAndStopsBatch() {
        confirms(false);publisher.processPending();
        verify(rabbit,times(1)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        verify(jdbc,never()).update(startsWith("UPDATE password_change_emails SET published_at"),eq(id));
    }
    @Test void brokerOutageLeavesReceiptPending() {
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException())).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        publisher.processPending();verify(jdbc,never()).update(startsWith("UPDATE password_change_emails SET published_at"),eq(id));
    }
    @Test void unroutableMandatoryMessageIsNotRecordedAsPublished() {
        doAnswer(invocation -> {
            var correlation=(CorrelationData)invocation.getArgument(3);
            correlation.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                    invocation.getArgument(2),312,"NO_ROUTE","","mail"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        publisher.processPending();verify(jdbc,never()).update(startsWith("UPDATE password_change_emails SET published_at"),eq(id));
    }
    @Test void missingMailConfigurationDoesNotPublish() {
        when(delivery.isConfigured()).thenReturn(false);publisher.processPending();verifyNoInteractions(rabbit);
    }
}
