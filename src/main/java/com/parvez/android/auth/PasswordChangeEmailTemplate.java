package com.parvez.android.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Branded security confirmation with escaped metadata and equivalent plain text. */
@Component
public class PasswordChangeEmailTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private final String template;
    private final DateTimeFormatter time;

    public PasswordChangeEmailTemplate(@Value("${app.password-reset.time-zone:Asia/Dhaka}") String timeZone) throws IOException {
        time = DateTimeFormatter.ofPattern("dd MMM uuuu, hh:mm:ss a '('VV xxx')'", Locale.ENGLISH)
                .withZone(ZoneId.of(timeZone));
        template = new ClassPathResource("templates/mail/password-change.html").getContentAsString(StandardCharsets.UTF_8);
    }

    public record Email(String text, String html) {}

    public Email render(Instant changedAt, String source, String ip, String browser, String device, String userAgent) {
        String changed = time.format(changedAt);
        String method = "RESET".equals(source) ? "Password recovery" : "Authenticated password change";
        var values = Map.of("changedAt", changed, "method", method, "ip", display(ip),
                "browser", display(browser), "device", display(device), "userAgent", display(userAgent));
        String html = PLACEHOLDER.matcher(template).replaceAll(match ->
                Matcher.quoteReplacement(HtmlUtils.htmlEscape(values.get(match.group(1)))));
        String text = """
                Booker | Account security
                Your password has been changed.

                Your Booker password was updated successfully. For your security,
                all previous account sessions have been revoked.

                Change details
                Changed at: %s
                Method: %s
                Browser: %s
                Device: %s
                Connection IP: %s

                Sign in again with your new password to continue using Booker.

                Wasn't you?
                Request a password reset immediately and contact your administrator
                to help secure your account.

                Technical details
                User-Agent (client-reported): %s
                Browser and device details are inferred from client-reported data
                and may be unavailable or inaccurate. The IP may belong to a proxy or VPN.

                Keep your credentials private. Booker will never ask for your password
                or reset token in an email reply.
                Booker | Your books, your space.
                """.formatted(changed, method, display(browser), display(device), display(ip), display(userAgent));
        return new Email(text, html);
    }

    private static String display(String value) { return value == null || value.isBlank() ? "Unknown" : value; }
}
