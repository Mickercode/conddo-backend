package io.conddo.api.web;

import io.conddo.api.security.RefreshCookies;
import io.conddo.api.web.dto.ForgotPasswordRequest;
import io.conddo.api.web.dto.LoginRequest;
import io.conddo.api.web.dto.LoginResponse;
import io.conddo.api.web.dto.ResetPasswordRequest;
import io.conddo.core.auth.AuthResult;
import io.conddo.core.auth.AuthService;
import io.conddo.core.auth.PasswordResetService;
import io.conddo.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (PRD §13.1). Login issues a short-lived access token
 * (body) and a rotating refresh token (httpOnly cookie). Refresh, logout, and
 * password-reset endpoints are added in later slices.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final RefreshCookies refreshCookies;

    public AuthController(AuthService authService, PasswordResetService passwordResetService,
                          RefreshCookies refreshCookies) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.refreshCookies = refreshCookies;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.tenantSlug(), request.email(), request.password());
        return tokenResponse(result);
    }

    /**
     * Exchanges the refresh cookie for a new access token and a rotated cookie.
     * A missing cookie surfaces as 401 via {@code AuthService}.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = RefreshCookies.COOKIE_NAME, required = false) String refreshToken) {
        AuthResult result = authService.refresh(refreshToken);
        return tokenResponse(result);
    }

    /** Revokes the refresh token's family and clears the cookie. Idempotent. */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = RefreshCookies.COOKIE_NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .body(ApiResponse.ok(null));
    }

    /**
     * Starts a password reset. Always 200, even for unknown accounts, so it does
     * not reveal whether an email is registered.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.tenantSlug(), request.email());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** Completes a password reset using the token from the reset link. */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private ResponseEntity<ApiResponse<LoginResponse>> tokenResponse(AuthResult result) {
        ResponseCookie cookie = refreshCookies.issue(result.refreshToken(), result.refreshTokenTtl());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(LoginResponse.from(result)));
    }
}
