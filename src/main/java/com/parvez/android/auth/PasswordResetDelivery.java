package com.parvez.android.auth;

import java.time.Duration;

/** Delivery boundary: password recovery owns token lifecycle, providers own transport and message formatting. */
public interface PasswordResetDelivery {
    boolean isConfigured();

    /** True when the provider accepts the email; false on delivery failure, without exposing secrets. */
    boolean send(String email, String token, Duration lifetime);
}
