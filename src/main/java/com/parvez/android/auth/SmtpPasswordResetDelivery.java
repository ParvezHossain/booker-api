package com.parvez.android.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** SMTP adapter; mail credentials and the reset landing page are configured only on the backend. */
@Component
public class SmtpPasswordResetDelivery implements PasswordResetDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetDelivery.class);
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final String resetUrl;

    public SmtpPasswordResetDelivery(ObjectProvider<JavaMailSender> mail,
            @Value("${app.password-reset.from:}") String from,
            @Value("${app.password-reset.url:}") String resetUrl) {
        this.mail = mail;
        this.from = from;
        this.resetUrl = resetUrl;
        if (!resetUrl.isBlank() && (!resetUrl.startsWith("https://") || resetUrl.contains("#"))) {
            throw new IllegalArgumentException("Password reset URL must use HTTPS and have no fragment");
        }
    }

    @Override
    public boolean isConfigured() {
        JavaMailSender sender = mail.getIfAvailable();
        return sender != null && !from.isBlank() && !resetUrl.isBlank()
                && (!(sender instanceof JavaMailSenderImpl smtp)
                    || (smtp.getHost() != null && !smtp.getHost().isBlank()));
    }

    @Override
    public boolean send(String email, String token, Duration lifetime) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Reset your Booker password");
        message.setText("Use this link to reset your password within " + lifetime.toMinutes() + " minutes:\n"
                + resetUrl + (resetUrl.contains("?") ? "&" : "?") + "token=" + token
                + "\nIf you did not request this, ignore this email.");
        try {
            mail.getObject().send(message);
            return true;
        } catch (MailException ex) {
            // Provider exceptions may contain the recipient or message; do not log them or the reset token.
            log.warn("Password reset email delivery failed");
            return false;
        }
    }
}
