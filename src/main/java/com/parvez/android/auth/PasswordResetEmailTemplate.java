package com.parvez.android.auth;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/** Trusted email layout with escaped token/URL values and a plain-text alternative. */
@Component
public class PasswordResetEmailTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private final String template;

    public PasswordResetEmailTemplate() throws IOException {
        template = new ClassPathResource("templates/mail/password-reset.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public record Email(String text, String html) {}

    public Email render(String token, Duration lifetime, String resetUrl, String tokenPageUrl) {
        String encodedToken = UriUtils.encode(token, StandardCharsets.UTF_8);
        String resetLink = resetUrl.isBlank() ? ""
                : resetUrl + (resetUrl.contains("?") ? "&" : "?") + "token=" + encodedToken;
        String copyLink = tokenPageUrl.isBlank() ? "" : tokenPageUrl + "#token=" + encodedToken;
        String actions = button(resetLink, "Reset password") + button(copyLink, "Open token copy page");
        var values = Map.of("token", HtmlUtils.htmlEscape(token),
                "minutes", Long.toString(lifetime.toMinutes()), "actions", actions);
        String html = PLACEHOLDER.matcher(template).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(values.get(match.group(1))));
        String text = """
                Booker | Account security
                Reset your password

                We received a request to reset your Booker password.
                This token expires in %d minutes and can be used once.

                Reset token:
                %s

                1. Copy the token above.
                2. Open the reset-password form in Booker or Swagger.
                3. Paste the token, choose a new password, and log in again.
                """.formatted(lifetime.toMinutes(), token);
        if (!resetLink.isEmpty()) text += "\nReset password: " + resetLink + "\n";
        if (!copyLink.isEmpty()) text += "\nOpen token copy page: " + copyLink + "\n";
        text += "\nDo not share this token. If you did not request this, ignore this email; "
                + "your password will stay the same.\nBooker will never ask you to send this token in a reply.\n";
        return new Email(text, html);
    }

    private static String button(String url, String label) {
        if (url.isEmpty()) return "";
        return "<p style=\"margin:16px 0 0\"><a href=\"" + HtmlUtils.htmlEscape(url)
                + "\" style=\"display:inline-block;background:#174b43;color:#ffffff;padding:14px 24px;"
                + "border-radius:8px;text-decoration:none;font-size:15px;font-weight:bold\">"
                + label + "</a></p>";
    }
}
