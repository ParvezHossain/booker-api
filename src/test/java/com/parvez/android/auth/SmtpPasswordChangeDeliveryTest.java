package com.parvez.android.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpPasswordChangeDeliveryTest {
    final PasswordChangeEmailTemplate template = new PasswordChangeEmailTemplate("Asia/Dhaka");

    SmtpPasswordChangeDeliveryTest() throws Exception {}
    @Test void emptyOrAbsentConfirmationSenderUsesRecoverySenderAndExplicitSenderOverridesIt() throws Exception {
        for (String from : List.of("", "confirmation@example.com")) {
            var factory = new DefaultListableBeanFactory();
            var sender = mock(JavaMailSender.class);
            when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
            factory.registerSingleton("mail", sender);
            var delivery = new SmtpPasswordChangeDelivery(factory.getBeanProvider(JavaMailSender.class), from,
                    "recovery@example.com", template);
            assertTrue(delivery.isConfigured());
            assertTrue(delivery.send("owner@example.com", Instant.parse("2026-10-04T10:00:00Z"),
                    "RESET", null, "Unknown", "Unknown", null));
            var capture = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
            verify(sender).send(capture.capture());
            var message = capture.getValue();
            message.saveChanges();
            assertEquals(from.isBlank() ? "recovery@example.com" : from, message.getFrom()[0].toString());
            assertEquals("owner@example.com", message.getAllRecipients()[0].toString());
            var mixed = (jakarta.mail.Multipart) message.getContent();
            var alternative = (jakarta.mail.Multipart) mixed.getBodyPart(0).getContent();
            String text = (String) alternative.getBodyPart(0).getContent();
            assertTrue(text.contains("Password recovery"));
            assertTrue(text.contains("Connection IP: Unknown"));
            assertTrue(text.contains("04 Oct 2026, 04:00:00 PM (Asia/Dhaka +06:00)"));
            assertTrue(alternative.getBodyPart(0).isMimeType("text/plain"));
            assertTrue(alternative.getBodyPart(1).isMimeType("text/html"));
        }
    }
    @Test void missingSenderOrTransportIsUnconfigured() {
        var factory = new DefaultListableBeanFactory();
        var provider = factory.getBeanProvider(JavaMailSender.class);
        assertFalse(new SmtpPasswordChangeDelivery(provider, "sender@example.com", "", template).isConfigured());
        factory.registerSingleton("mail", mock(JavaMailSender.class));
        assertFalse(new SmtpPasswordChangeDelivery(provider, "", "", template).isConfigured());
    }
}
