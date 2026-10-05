package com.parvez.android.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matching Booker email branding, escaped content, copyable token and optional HTTPS app link. */
@Component
public class EmailActivationTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private final String template;
    private final DateTimeFormatter time;
    private final String url;

    public EmailActivationTemplate(@Value("${app.email-activation.time-zone:Asia/Dhaka}") String timeZone,
                                   @Value("${app.email-activation.url:}") String url) throws IOException {
        time = DateTimeFormatter.ofPattern("dd MMM uuuu, hh:mm:ss a '('VV xxx')'", Locale.ENGLISH).withZone(ZoneId.of(timeZone));
        this.url = url;
        if (!url.isBlank()) {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException("Email activation URL must use HTTPS without credentials, query or fragment");
        }
        template = new ClassPathResource("templates/mail/email-activation.html").getContentAsString(StandardCharsets.UTF_8);
    }

    public record Email(String text, String html) {}

    public Email render(String email, String workspaceName, String token, Instant expiresAt) {
        String expiry = time.format(expiresAt);
        String link = url.isBlank() ? "" : url + "#token=" + token + "&expiresAt=" + expiresAt.toEpochMilli();
        String action = link.isEmpty() ? "" : "<p style=\"margin:24px 0 0;text-align:center;\"><a href=\""
                + HtmlUtils.htmlEscape(link) + "\" style=\"display:inline-block;padding:14px 24px;background:#174b43;border-radius:8px;color:#ffffff;text-decoration:none;font-size:14px;font-weight:bold;\">Open activation screen</a></p>"
                + "<p style=\"margin:10px 0 0;color:#697d75;font-size:12px;line-height:1.7;text-align:center;\">Review and confirm activation in Booker.</p>";
        var values = Map.of("email", email, "workspaceName", workspaceName, "token", token, "expiresAt", expiry);
        String html = PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(
                "action".equals(match.group(1)) ? action : HtmlUtils.htmlEscape(values.get(match.group(1)))));
        String text = """
                Booker | Workspace activation
                Welcome to Booker.

                Confirm %s to activate your workspace: %s
                Activation token: %s
                Expires on: %s

                Copy the token into Booker's activation screen and confirm activation.
                This token works once. After activation, sign in with your signup password.
                If it expires, request a replacement activation email from Booker.

                Only activate a workspace you created. If this wasn't you, ignore this email.
                Do not share the token. Booker will never ask for your password or activation
                token in an email reply.
                """.formatted(email, workspaceName, token, expiry);
        if (!link.isEmpty()) text += "\nOpen activation screen: " + link + "\n";
        return new Email(text, html);
    }
}
