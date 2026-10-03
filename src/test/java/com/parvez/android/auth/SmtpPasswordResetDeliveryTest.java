package com.parvez.android.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpPasswordResetDeliveryTest {
    @Test void resetUrlKeepsConfiguredQueryParametersAndFormatsEmail() {
        var sender = mock(JavaMailSender.class);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("mail", sender);
        var delivery = new SmtpPasswordResetDelivery(factory.getBeanProvider(JavaMailSender.class),
                "books@example.com", "https://example.com/reset?client=android");
        assertTrue(delivery.isConfigured());
        assertTrue(delivery.send("owner@example.com", "secret-token", Duration.ofMinutes(30)));
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(capture.capture());
        assertEquals("books@example.com", capture.getValue().getFrom());
        assertArrayEquals(new String[]{"owner@example.com"}, capture.getValue().getTo());
        assertTrue(capture.getValue().getText().contains("https://example.com/reset?client=android&token=secret-token"));
    }

    @Test void absentOrBlankSmtpHostIsNotConfiguredAndUnsafeUrlsAreRejected() {
        var factory = new DefaultListableBeanFactory();
        var provider = factory.getBeanProvider(JavaMailSender.class);
        assertFalse(new SmtpPasswordResetDelivery(provider, "books@example.com", "https://example.com/reset").isConfigured());
        factory.registerSingleton("mail", new JavaMailSenderImpl());
        assertFalse(new SmtpPasswordResetDelivery(provider, "books@example.com", "https://example.com/reset").isConfigured());
        assertThrows(IllegalArgumentException.class, () -> new SmtpPasswordResetDelivery(provider, "books@example.com", "http://example.com/reset"));
        assertThrows(IllegalArgumentException.class, () -> new SmtpPasswordResetDelivery(provider, "books@example.com", "https://example.com/reset#token"));
    }
}
