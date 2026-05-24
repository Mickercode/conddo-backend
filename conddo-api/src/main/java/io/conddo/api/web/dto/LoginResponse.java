package io.conddo.api.web.dto;

import io.conddo.core.auth.AuthResult;

import java.util.UUID;

/**
 * Login response body. The access token is returned here for the client to send
 * as a {@code Bearer} token; the refresh token is delivered separately as an
 * httpOnly cookie and never appears in the body.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String role
) {
    public static LoginResponse from(AuthResult result) {
        return new LoginResponse(
                result.accessToken(), "Bearer", result.accessTokenTtl().toSeconds(),
                result.userId(), result.role());
    }
}
