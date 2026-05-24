package io.conddo.api.security;

import io.conddo.core.auth.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the refresh-token cookie. It is {@code HttpOnly} (invisible to JS),
 * {@code SameSite=Strict} (not sent on cross-site requests — the CSRF defence,
 * given the API is otherwise stateless), and scoped to {@code /auth} so it only
 * travels to the refresh/logout endpoints. {@code Secure} is configurable so
 * local http development still works.
 */
@Component
public class RefreshCookies {

    public static final String COOKIE_NAME = "conddo_rt";
    private static final String PATH = "/auth";

    private final boolean secure;
    private final String sameSite;

    public RefreshCookies(AuthProperties properties) {
        this.secure = properties.cookieSecure();
        this.sameSite = properties.cookieSameSite();
    }

    public ResponseCookie issue(String rawToken, Duration maxAge) {
        return base(rawToken).maxAge(maxAge).build();
    }

    /** A cookie that immediately expires the refresh cookie (logout). */
    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH);
    }
}
