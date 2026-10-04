package com.parvez.android.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordChangeContextTest {
    @Test void missingAndNativeClientAgentsDoNotInventBrowserOrDeviceIdentity() {
        assertEquals("Unknown", PasswordChangeContext.unknown().browser());
        assertEquals("Unknown", PasswordChangeContext.unknown().device());
        var nativeClient = new PasswordChangeContext("127.0.0.1", "okhttp/4.12.0");
        assertEquals("Unknown", nativeClient.browser());
        assertEquals("Unknown", nativeClient.device());
    }

    @Test void untrustedMetadataIsBoundedAndControlCharactersAreRemoved() {
        var context = new PasswordChangeContext(" 2001:db8::1\r\n", "Firefox/140.0\u0000\r\n" + "x".repeat(800));
        assertEquals("2001:db8::1", context.ipAddress());
        assertEquals(512, context.userAgent().length());
        assertFalse(context.userAgent().contains("\u0000"));
        assertFalse(context.userAgent().contains("\n"));
        assertEquals("Firefox", context.browser());
    }
}
