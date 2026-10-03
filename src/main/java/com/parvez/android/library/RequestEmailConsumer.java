package com.parvez.android.library;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "books.requests.email.enabled", havingValue = "true", matchIfMissing = true)
public class RequestEmailConsumer {
    private final PublicRequestEmailDelivery delivery;
    public RequestEmailConsumer(PublicRequestEmailDelivery delivery) { this.delivery = delivery; }

    @RabbitListener(queues = "${books.requests.email.queue:booker.request-emails}",
            containerFactory = "requestEmailListenerFactory")
    public void receive(Message message) {
        UUID id;
        try {
            if (message.getBody().length != 36) throw new IllegalArgumentException();
            String token = new String(message.getBody(), StandardCharsets.US_ASCII);
            id = UUID.fromString(token);
            if (!id.toString().equals(token)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new AmqpRejectAndDontRequeueException("Invalid request email receipt ID");
        }
        delivery.deliver(id);
    }
}
