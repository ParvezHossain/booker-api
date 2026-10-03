package com.parvez.android.library;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PublicRequestEmailTemplateTest {
    @Test void escapesSubmittedValuesWithoutReinterpretingTemplatePlaceholders() throws Exception {
        var request = new PublicLibraryBookRequest(UUID.randomUUID(), "<script>alert('x')</script> {{author}}",
                "Author & Co", UUID.randomUUID(), "reader@example.com", "PENDING", null, null,
                Instant.parse("2026-10-03T10:00:00Z"), null);
        var email = new PublicRequestEmailTemplate().submission(request, "Workspace <img src=x> বাংলা");
        assertFalse(email.html().contains("<script>"));
        assertFalse(email.html().contains("<img src=x>"));
        assertTrue(email.html().contains("&lt;script&gt;"));
        assertTrue(email.html().contains("Author &amp; Co"));
        assertTrue(email.html().contains("{{author}}"));
        assertTrue(email.html().contains("বাংলা"));
        assertTrue(email.html().contains(request.id().toString()));
        assertTrue(email.html().contains(request.workspaceId().toString()));
        assertTrue(email.text().contains(request.title()));
        assertTrue(email.text().contains(request.requesterEmail()));
        assertTrue(email.text().contains("2026-10-03T10:00:00Z"));
        assertEquals("New public library book request | Booker", email.subject());
    }
}
