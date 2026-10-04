package com.parvez.android.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }


    private static final String[] SWAGGER_WHITELIST = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };


    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, com.parvez.android.auth.TokenService tokens) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.GET, SWAGGER_WHITELIST).permitAll()

                        // These public routes authenticate through credentials, a refresh/reset token, or bound OAuth state.
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/integrations/google-drive/callback", "/password-reset-token").permitAll()
                        .requestMatchers("/api/admin/public-book-requests/**", "/api/admin/public-book-requests").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/admin/login-history", "/api/admin/password-change-history").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/workspace/login-history", "/api/workspace/password-change-history").authenticated()
                        .requestMatchers("/api/public-book-requests").authenticated()
                        // Match progress before general PUT management: workspace users may save progress.
                        .requestMatchers(HttpMethod.POST, "/api/public-books", "/api/public-books/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/public-books/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/public-books/*/reading-progress").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/public-books/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/public-books", "/api/public-books/**").authenticated()
                        .requestMatchers(HttpMethod.HEAD, "/api/public-books/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
                        .requestMatchers("/api/books/**", "/api/workspace", "/api/integrations/google-drive/**").authenticated()
                        .anyRequest().denyAll())
                .formLogin(form -> form.disable())
                .httpBasic(Customizer.withDefaults())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .decoder(tokens::decodeAccess)
                        .jwtAuthenticationConverter(tokens::authentication)))
                .build();
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:http://localhost:4200}") String origins) {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOrigins(java.util.Arrays.stream(origins.split(",")).map(String::strip).toList());
        corsConfiguration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE"));
        corsConfiguration.setAllowedHeaders(List.of("Authorization", "Cache-Control", "Content-Type", "Last-Event-ID", "Range", "If-Range", "Idempotency-Key"));
        corsConfiguration.setExposedHeaders(List.of("Content-Length", "Content-Range", "Accept-Ranges", "Content-Disposition", "ETag", "Retry-After"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        return source;
    }

}
