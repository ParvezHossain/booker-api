package com.parvez.android.auth;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PasswordChangeEmailTemplateTest {
    final PasswordChangeEmailTemplate template = new PasswordChangeEmailTemplate("Asia/Dhaka");
    final Instant changedAt = Instant.parse("2026-10-04T11:15:13Z");

    PasswordChangeEmailTemplateTest() throws Exception {}

    @Test void rendersChangeAndRecoveryWithMatchingSecurityGuidanceAndLocalizedTime() {
        for (String source : new String[]{"CHANGE", "RESET"}) {
            var email = template.render(changedAt, source, "192.0.2.1", "Chrome", "Computer / Linux", "Mozilla/5.0");
            for (String content : new String[]{email.text(), email.html()}) {
                assertTrue(content.contains("04 Oct 2026, 05:15:13 PM (Asia/Dhaka +06:00)"));
                assertTrue(content.contains("RESET".equals(source) ? "Password recovery" : "Authenticated password change"));
                assertTrue(content.contains("192.0.2.1"));
                assertTrue(content.contains("Chrome"));
                assertTrue(content.contains("Computer / Linux"));
                assertTrue(content.contains("all previous account sessions have been revoked"));
                assertTrue(content.contains("Request a password reset immediately"));
                assertTrue(content.contains("User-Agent (client-reported)"));
            }
        }
    }

    @Test void escapesEveryClientSuppliedFieldWithoutInterpretingReplacementCharacters() {
        String malicious = "<script>alert(1)</script> & $1 \\";
        var email = template.render(changedAt, "CHANGE", malicious, malicious, malicious, malicious);
        assertFalse(email.html().contains("<script>"));
        assertEquals(4, email.html().split("&lt;script&gt;", -1).length - 1);
        assertTrue(email.html().contains("&amp; $1 \\"));
        assertTrue(email.text().contains(malicious));
    }

    @Test void unavailableMetadataIsShownAsUnknownInBothAlternatives() {
        var email = template.render(changedAt, "RESET", null, "", " ", null);
        assertTrue(email.text().contains("Browser: Unknown"));
        assertTrue(email.text().contains("Device: Unknown"));
        assertTrue(email.text().contains("Connection IP: Unknown"));
        assertTrue(email.text().contains("User-Agent (client-reported): Unknown"));
        assertEquals(4, email.html().split("Unknown", -1).length - 1);
    }
}
