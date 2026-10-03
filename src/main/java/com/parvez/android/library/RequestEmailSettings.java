package com.parvez.android.library;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("books.requests.email")
public record RequestEmailSettings(
        @DefaultValue("booker.request-emails") @NotBlank String queue,
        @DefaultValue("20") @Min(1) @Max(100) int batchSize,
        @DefaultValue("1000") @Min(1) @Max(100000) int queueLimit,
        @DefaultValue("5") @Min(1) @Max(20) int maxAttempts,
        @DefaultValue("30") @Min(1) @Max(3600) int retrySeconds,
        @DefaultValue("300") @Min(30) @Max(86400) int redispatchSeconds) {}
