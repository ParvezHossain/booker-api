package com.parvez.android.auth;

import java.time.Instant;

/** Durable acceptance boundary; SMTP and RabbitMQ publication happen after request commit. */
public interface PasswordResetDelivery {
    boolean isConfigured();

    /** Persist the encrypted email receipt in the token issuance transaction. */
    void enqueue(String email, String token, Instant expiresAt);
}
