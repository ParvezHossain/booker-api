package com.parvez.android.auth;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpPasswordResetDeliveryTest {
    final PasswordResetEmailTemplate template = new PasswordResetEmailTemplate();

    SmtpPasswordResetDeliveryTest() throws Exception {}

    SmtpPasswordResetDelivery delivery(DefaultListableBeanFactory factory, String from, String url, String copyUrl) {
        return new SmtpPasswordResetDelivery(factory.getBeanProvider(JavaMailSender.class), from, url, copyUrl, template);
    }

    JavaMailSender sender(DefaultListableBeanFactory factory) {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        factory.registerSingleton("mail", sender);
        return sender;
    }

    Multipart alternatives(JavaMailSender sender) throws Exception {
        var capture = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(capture.capture());
        var message = capture.getValue();
        message.saveChanges();
        assertEquals("books@example.com", message.getFrom()[0].toString());
        assertEquals("owner@example.com", message.getAllRecipients()[0].toString());
        assertEquals("Reset your Booker password", message.getSubject());
        return (Multipart) ((Multipart) message.getContent()).getBodyPart(0).getContent();
    }

    @Test void blankResetUrlSendsHtmlAndPlainTokenWithoutAWebApp() throws Exception {
        var factory = new DefaultListableBeanFactory();
        var sender = sender(factory);
        var delivery = delivery(factory, "books@example.com", "", "");
        assertTrue(delivery.isConfigured());
        assertTrue(delivery.send("owner@example.com", "secret-token", Duration.ofMinutes(30)));
        var parts = alternatives(sender);
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(parts.getBodyPart(1).isMimeType("text/html"));
        String text = parts.getBodyPart(0).getContent().toString();
        String html = parts.getBodyPart(1).getContent().toString();
        assertTrue(text.contains("Reset token:\nsecret-token\n"));
        assertTrue(text.contains("30 minutes"));
        assertTrue(html.contains("secret-token"));
        assertTrue(html.contains("Single use"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("href="));
        assertFalse(text.contains("token="));
    }

    @Test void copyButtonUsesFragmentWhileExistingResetLinkRetainsItsQuery() throws Exception {
        var factory = new DefaultListableBeanFactory();
        var sender = sender(factory);
        var delivery = delivery(factory, "books@example.com", "https://example.com/reset?client=android",
                "https://api.example.com/password-reset-token");
        String token = "A".repeat(43);
        assertTrue(delivery.send("owner@example.com", token, Duration.ofMinutes(30)));
        var parts = alternatives(sender);
        String text = parts.getBodyPart(0).getContent().toString();
        String html = parts.getBodyPart(1).getContent().toString();
        assertTrue(text.contains("https://example.com/reset?client=android&token=" + token));
        assertTrue(html.contains("https://example.com/reset?client=android&amp;token=" + token));
        assertTrue(html.contains("href=\"https://api.example.com/password-reset-token#token=" + token + "\""));
        assertTrue(html.contains("Open token copy page"));
        assertFalse(html.contains("password-reset-token?token="));
    }

    @Test void tokenOnlyCopyEmailUsesConfiguredLifetimeAndEscapesDynamicValues() {
        var content = template.render("<script>unsafe</script>", Duration.ofMinutes(15), "", "https://api.example.com/password-reset-token");
        assertTrue(content.html().contains("&lt;script&gt;unsafe&lt;/script&gt;"));
        assertFalse(content.html().contains("<script>"));
        assertTrue(content.html().contains("15 minutes"));
        assertTrue(content.html().contains("#token=%3Cscript%3Eunsafe%3C%2Fscript%3E"));
        assertFalse(content.html().contains("{{"));
    }

    @Test void tokenEmailStillRequiresSenderAndSmtpHost() {
        var sender = new JavaMailSenderImpl();
        sender.setHost("smtp.example.com");
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("mail", sender);
        assertTrue(delivery(factory, "books@example.com", "", "").isConfigured());
        assertFalse(delivery(factory, "", "", "").isConfigured());
        sender.setHost("");
        assertFalse(delivery(factory, "books@example.com", "", "").isConfigured());
    }

    @Test void absentSmtpIsNotConfiguredAndUnsafeUrlsAreRejected() {
        var factory = new DefaultListableBeanFactory();
        assertFalse(delivery(factory, "books@example.com", "https://example.com/reset", "").isConfigured());
        assertThrows(IllegalArgumentException.class, () -> delivery(factory, "books@example.com", "http://example.com/reset", ""));
        assertThrows(IllegalArgumentException.class, () -> delivery(factory, "books@example.com", "https://example.com/reset#token", ""));
        for (String copyUrl : new String[]{"javascript:alert(1)", "http://example.com/password-reset-token",
                "https://user:password@example.com/copy", "https://example.com/copy?token=x", "https://example.com/copy#token=x",
                "http://192.168.example.com/copy", "http://192.168.999.1/copy"}) {
            assertThrows(IllegalArgumentException.class, () -> delivery(factory, "books@example.com", "", copyUrl));
        }
        assertDoesNotThrow(() -> delivery(factory, "books@example.com", "", "http://192.168.0.122:8080/password-reset-token"));
    }

    @Test void smtpFailureStillReportsFailureToTokenLifecycle() {
        var factory = new DefaultListableBeanFactory();
        var sender = sender(factory);
        doThrow(new MailSendException("offline")).when(sender).send(any(MimeMessage.class));
        assertFalse(delivery(factory, "books@example.com", "", "").send("owner@example.com", "secret-token", Duration.ofMinutes(30)));
    }
}
