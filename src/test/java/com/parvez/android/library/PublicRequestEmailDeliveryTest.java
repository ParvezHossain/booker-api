package com.parvez.android.library;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicRequestEmailDeliveryTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final TransactionTemplate transaction = mock(TransactionTemplate.class);
    @SuppressWarnings("unchecked") final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    final JavaMailSender sender = mock(JavaMailSender.class);
    final UUID id = UUID.randomUUID();
    final RequestEmailSettings settings = new RequestEmailSettings("mail",20,1000,5,30,300);
    PublicRequestEmailDelivery delivery;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        when(provider.getIfAvailable()).thenReturn(sender);
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>)invocation.getArgument(0)).accept(new SimpleTransactionStatus());
            return null;
        }).when(transaction).executeWithoutResult(any());
        delivery = new PublicRequestEmailDelivery(jdbc,transaction,provider,"booker@example.com",settings);
    }
    Map<String,Object> receipt(int attempts) {
        var row = new HashMap<String,Object>();
        row.put("recipient","reader@example.com");row.put("message","Your requested book was accepted");
        row.put("subject","Your Booker public library request");row.put("attempts",attempts);
        return row;
    }
    void pending(Map<String,Object> row) { when(jdbc.queryForList(anyString(),eq(id))).thenReturn(List.of(row)); }

    @Test void sendsDecisionAndDeletesOnlyItsReceipt() {
        pending(receipt(0)); delivery.deliver(id);
        verify(sender).send(argThat((SimpleMailMessage message) -> "reader@example.com".equals(message.getTo()[0]) && message.getText().contains("accepted")));
        verify(jdbc).update("DELETE FROM public_request_emails WHERE id=?",id);
    }
    @Test void failedSmtpSchedulesDelayedRetryWithoutDeletingReceipt() {
        pending(receipt(0));doThrow(new MailSendException("offline")).when(sender).send(any(SimpleMailMessage.class));
        delivery.deliver(id);
        verify(jdbc).update(contains("UPDATE public_request_emails"),eq(1),eq(30L),eq(false),eq(id));
        verify(jdbc,never()).update(startsWith("DELETE"),any(Object[].class));
    }
    @Test void repeatedFailureParksReceiptAfterMaximumAttempts() {
        pending(receipt(4));doThrow(new MailSendException("offline")).when(sender).send(any(SimpleMailMessage.class));
        delivery.deliver(id);
        verify(jdbc).update(contains("UPDATE public_request_emails"),eq(5),eq(480L),eq(true),eq(id));
        verify(jdbc,never()).update(startsWith("DELETE"),any(Object[].class));
    }
    @Test void sendsUtf8HtmlAndPlainText() throws Exception {
        var row=receipt(0);row.put("message","Book: বাংলা");row.put("html_message","<h1>Book: বাংলা</h1>");pending(row);
        var mime=new MimeMessage(Session.getInstance(new Properties()));when(sender.createMimeMessage()).thenReturn(mime);
        delivery.deliver(id);verify(sender).send(mime);mime.saveChanges();
        assertEquals("reader@example.com",mime.getAllRecipients()[0].toString());
        var alternatives=(Multipart)((Multipart)mime.getContent()).getBodyPart(0).getContent();
        assertTrue(alternatives.getBodyPart(0).isMimeType("text/plain"));assertTrue(alternatives.getBodyPart(1).isMimeType("text/html"));
        assertEquals(row.get("message"),alternatives.getBodyPart(0).getContent());assertEquals(row.get("html_message"),alternatives.getBodyPart(1).getContent());
        verify(jdbc).update("DELETE FROM public_request_emails WHERE id=?",id);
    }
    @Test void missingConfigurationDefersWithoutConsumingAttempt() {
        pending(receipt(2));when(provider.getIfAvailable()).thenReturn(null);
        assertFalse(delivery.isConfigured());delivery.deliver(id);
        verify(jdbc).update(contains("UPDATE public_request_emails"),eq(2),eq(60L),eq(false),eq(id));
        verifyNoInteractions(sender);
    }
    @Test void duplicateOrNotYetDueTokenDoesNotSendAgain() {
        when(jdbc.queryForList(anyString(),eq(id))).thenReturn(List.of());delivery.deliver(id);verifyNoInteractions(sender);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
