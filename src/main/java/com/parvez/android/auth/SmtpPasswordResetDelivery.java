package com.parvez.android.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import jakarta.mail.MessagingException;

import java.net.URI;
import java.time.Instant;

/** SMTP adapter; sends a token directly or a link to an optional HTTPS reset page. */
@Component
public class SmtpPasswordResetDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetDelivery.class);
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final String resetUrl;
    private final String tokenPageUrl;
    private final PasswordResetEmailTemplate template;

    public SmtpPasswordResetDelivery(ObjectProvider<JavaMailSender> mail,
            @Value("${app.password-reset.from:}") String from,
            @Value("${app.password-reset.url:}") String resetUrl,
            @Value("${app.password-reset.token-page-url:}") String tokenPageUrl,
            PasswordResetEmailTemplate template) {
        this.mail = mail;
        this.from = from;
        this.resetUrl = resetUrl;
        this.tokenPageUrl = tokenPageUrl;
        this.template = template;
        if (!resetUrl.isBlank() && (!resetUrl.startsWith("https://") || resetUrl.contains("#"))) {
            throw new IllegalArgumentException("Password reset URL must use HTTPS and have no fragment");
        }
        if (!tokenPageUrl.isBlank()) validateTokenPageUrl(tokenPageUrl);
    }

    public boolean isConfigured() {
        JavaMailSender sender = mail.getIfAvailable();
        return sender != null && !from.isBlank()
                && (!(sender instanceof JavaMailSenderImpl smtp)
                    || (smtp.getHost() != null && !smtp.getHost().isBlank()));
    }

    public boolean send(String email, String token, Instant expiresAt) {
        try {
            var sender = mail.getObject();
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED, "UTF-8");
            var content = template.render(token, expiresAt, resetUrl, tokenPageUrl);
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Reset your Booker password");
            helper.setText(content.text(), content.html());
            sender.send(message);
            return true;
        } catch (MailException | MessagingException ex) {
            // Provider exceptions may contain the recipient or message; do not log them or the reset token.
            log.warn("Password reset email delivery failed");
            return false;
        }
    }

    private static void validateTokenPageUrl(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid password reset token page URL"); }
        String host = uri.getHost();
        boolean local = isLocalHost(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("Token page URL must use HTTPS (HTTP only for local development), without credentials, query or fragment");
        }
    }

    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        if (host.equals("localhost") || host.equals("[::1]")) return true;
        if (!host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) return false;
        int[] parts = java.util.Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
        if (java.util.Arrays.stream(parts).anyMatch(part -> part > 255)) return false;
        return parts[0] == 127 || parts[0] == 10 || (parts[0] == 192 && parts[1] == 168)
                || (parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31);
    }
}
