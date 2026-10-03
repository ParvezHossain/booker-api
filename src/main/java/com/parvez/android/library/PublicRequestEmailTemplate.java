package com.parvez.android.library;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** Renders trusted markup with escaped, untrusted book/workspace details. */
@Component
public class PublicRequestEmailTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private final String template;

    public PublicRequestEmailTemplate() throws IOException {
        template = new ClassPathResource("templates/mail/public-book-request.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public record Email(String subject, String text, String html) {}

    public Email submission(PublicLibraryBookRequest request, String workspaceName) {
        var values = Map.of(
                "title", request.title(), "author", request.authorName(),
                "workspace", workspaceName, "workspaceId", request.workspaceId().toString(),
                "requester", request.requesterEmail(), "requestId", request.id().toString(),
                "createdAt", request.createdAt().toString());
        String html = PLACEHOLDER.matcher(template).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(HtmlUtils.htmlEscape(values.get(match.group(1)))));
        String text = """
                New public library book request

                A workspace has requested a book for the Booker public library.

                Book title: %s
                Author: %s
                Workspace: %s
                Workspace ID: %s
                Requested by: %s
                Request ID: %s
                Submitted at (UTC): %s

                Please review this pending request in your administration interface.
                Accept it with a validated PDF to add the book to the public library,
                or reject it. The requester will be notified of your decision.

                Booker | Public Library
                This is an automated notification; replies are not processed.
                """.formatted(request.title(), request.authorName(), workspaceName,
                request.workspaceId(), request.requesterEmail(), request.id(), request.createdAt());
        return new Email("New public library book request | Booker", text, html);
    }
}
