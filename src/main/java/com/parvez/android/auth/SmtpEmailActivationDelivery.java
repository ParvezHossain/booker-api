package com.parvez.android.auth;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Background SMTP adapter; provider errors and activation secrets never enter logs. */
@Component
public class SmtpEmailActivationDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmtpEmailActivationDelivery.class);
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final EmailActivationTemplate template;
    private final EmailActivationCipher cipher;

    public SmtpEmailActivationDelivery(ObjectProvider<JavaMailSender> mail,
            @Value("${app.email-activation.from:}") String from, EmailActivationTemplate template, EmailActivationCipher cipher) {
        this.mail = mail; this.from = from; this.template = template; this.cipher = cipher;
    }

    public boolean isConfigured() {
        JavaMailSender sender = mail.getIfAvailable();
        return cipher.isConfigured() && sender != null && !from.isBlank()
                && (!(sender instanceof JavaMailSenderImpl smtp) || (smtp.getHost() != null && !smtp.getHost().isBlank()));
    }

    public boolean send(String email, String token, Instant expiresAt, String workspaceName) {
        try {
            var sender = mail.getObject();
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED, "UTF-8");
            var content = template.render(email, workspaceName, token, expiresAt);
            helper.setFrom(from); helper.setTo(email); helper.setSubject("Activate your Booker workspace");
            helper.setText(content.text(), content.html());
            sender.send(message);
            return true;
        } catch (MailException | MessagingException failure) {
            log.warn("Email activation delivery failed");
            return false;
        }
    }
}
