package com.parvez.android.auth;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.password-reset.email.enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetEmailConsumer {
    private final PasswordResetEmailWorker worker;
    public PasswordResetEmailConsumer(PasswordResetEmailWorker worker) { this.worker = worker; }

    @RabbitListener(queues = "${app.password-reset.email.queue:booker.password-reset-emails}",
            containerFactory = "passwordResetEmailListenerFactory")
    public void receive(Message message) {
        UUID id;
        try {
            if (message.getBody().length != 36) throw new IllegalArgumentException();
            String value = new String(message.getBody(), StandardCharsets.US_ASCII);
            id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new AmqpRejectAndDontRequeueException("Invalid password reset email receipt ID");
        }
        worker.deliver(id);
    }
}
