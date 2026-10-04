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

/** Security confirmation with escaped client metadata and no authentication secrets. */
@Component
public class SmtpPasswordChangeDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordChangeDelivery.class);
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final PasswordChangeEmailTemplate template;

    public SmtpPasswordChangeDelivery(ObjectProvider<JavaMailSender> mail,
            @Value("${app.password-change.email.from:}") String from,
            @Value("${app.password-reset.from:}") String resetFrom,
            PasswordChangeEmailTemplate template) {
        this.mail = mail;
        this.from = from.isBlank() ? resetFrom : from;
        this.template = template;
    }

    public boolean isConfigured() {
        var sender = mail.getIfAvailable();
        return sender != null && !from.isBlank()
                && (!(sender instanceof JavaMailSenderImpl smtp)
                    || (smtp.getHost() != null && !smtp.getHost().isBlank()));
    }

    public boolean send(String email, Instant changedAt, String source, String ip, String browser, String device, String userAgent) {
        try {
            var content = template.render(changedAt, source, ip, browser, device, userAgent);
            var sender = mail.getObject();
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Your Booker password has changed");
            helper.setText(content.text(), content.html());
            sender.send(message);
            return true;
        } catch (MailException | MessagingException failure) {
            log.warn("Password change notification SMTP delivery failed");
            return false;
        }
    }

}
