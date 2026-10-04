package com.parvez.android.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/** Login/password request context: connection address and bounded, untrusted client metadata. */
public record PasswordChangeContext(String ipAddress, String userAgent) {
    public PasswordChangeContext {
        ipAddress = clean(ipAddress, 64);
        userAgent = clean(userAgent, 512);
    }

    public static PasswordChangeContext from(HttpServletRequest request) {
        return new PasswordChangeContext(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    public static PasswordChangeContext unknown() { return new PasswordChangeContext(null, null); }

    public String browser() {
        String agent = agent();
        if (agent.contains("edg/") || agent.contains("edga/") || agent.contains("edgios/")) return "Edge";
        if (agent.contains("opr/") || agent.contains("opera")) return "Opera";
        if (agent.contains("samsungbrowser/")) return "Samsung Internet";
        if (agent.contains("firefox/") || agent.contains("fxios/")) return "Firefox";
        if (agent.contains("chrome/") || agent.contains("crios/")) return "Chrome";
        if (agent.contains("safari/") && agent.contains("version/")) return "Safari";
        return "Unknown";
    }

    public String device() {
        String agent = agent();
        if (agent.contains("ipad")) return "iPad / iPadOS";
        if (agent.contains("iphone")) return "iPhone / iOS";
        if (agent.contains("android")) return agent.contains("mobile") ? "Mobile / Android" : "Tablet / Android";
        if (agent.contains("windows")) return "Computer / Windows";
        if (agent.contains("macintosh") || agent.contains("mac os x")) return "Computer / macOS";
        if (agent.contains("cros")) return "Computer / ChromeOS";
        if (agent.contains("linux")) return "Computer / Linux";
        return "Unknown";
    }

    private String agent() { return userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT); }

    private static String clean(String value, int limit) {
        if (value == null) return null;
        String cleaned = value.codePoints().filter(c -> !Character.isISOControl(c)).limit(limit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
