package com.parvez.android.library;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublicRequestEmailDeliveryTest {
    @Test @SuppressWarnings("unchecked") void sendsCommittedDecisionAndDeletesReceiptOnlyOnSuccess() {
        var jdbc=mock(JdbcTemplate.class);
        var transaction=mock(TransactionTemplate.class);
        var provider=(ObjectProvider<JavaMailSender>)mock(ObjectProvider.class);
        var sender=mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(transaction.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        UUID id=UUID.randomUUID();
        var row=Map.<String,Object>of("request_id",id,"recipient","reader@example.com","message","Your requested book was accepted");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row),List.of());
        new PublicRequestEmailDelivery(jdbc,transaction,provider,"booker@example.com").processPending();
        verify(sender).send(argThat((SimpleMailMessage message) -> "reader@example.com".equals(message.getTo()[0]) && message.getText().contains("accepted")));
        verify(jdbc).update("DELETE FROM public_request_emails WHERE request_id=?",id);
    }
    @Test @SuppressWarnings("unchecked") void failedSmtpRetainsReceiptForRetry() {
        var jdbc=mock(JdbcTemplate.class);
        var transaction=mock(TransactionTemplate.class);
        var provider=(ObjectProvider<JavaMailSender>)mock(ObjectProvider.class);
        var sender=mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(transaction.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("request_id",UUID.randomUUID(),"recipient","reader@example.com","message","rejected")));
        doThrow(new MailSendException("offline")).when(sender).send(any(SimpleMailMessage.class));
        new PublicRequestEmailDelivery(jdbc,transaction,provider,"booker@example.com").processPending();
        verify(jdbc,never()).update(anyString(),any(Object.class));
    }
}
