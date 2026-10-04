package com.parvez.android.auth;

import java.time.Instant;

/** Delivery boundary: password recovery owns token lifecycle, providers own transport and message formatting. */
public interface PasswordResetDelivery {
    boolean isConfigured();

    /** True when the provider accepts the email; false on delivery failure, without exposing secrets. */
    boolean send(String email, String token, Instant expiresAt);
}
