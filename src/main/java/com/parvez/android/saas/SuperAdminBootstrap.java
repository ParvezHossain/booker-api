package com.parvez.android.saas;

import jakarta.validation.Validation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Optional on existing installations; configure both secrets for initial system-owner setup.
 */
@Component
public class SuperAdminBootstrap implements ApplicationRunner {
    private final WorkspaceAccounts accounts;
    private final String email;
    private final String password;

    public SuperAdminBootstrap(WorkspaceAccounts accounts,
                               @Value("${app.super-admin.email:}") String email,
                               @Value("${app.super-admin.password:}") String password) {
        this.accounts = accounts;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (email.isBlank() && password.isBlank()) return;
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            if (!factory.getValidator().validate(new Setup(email, password)).isEmpty())
                throw new IllegalStateException("Super Admin setup requires a valid email and a 12–64 character password");
        }
        accounts.provisionSuperAdmin(email, password);
    }

    private record Setup(@NotBlank @Email @Size(max = 254) String email,
                         @NotBlank @Size(min = 12, max = 64) String password) {
    }
}
