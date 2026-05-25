package io.conddo.studio.web.dto;

import io.conddo.studio.auth.StudioAuthService.AuthResult;

/** Login/refresh response: the access token + its TTL, the refresh token, and the staff profile. */
public record AuthResponse(String accessToken, long expiresIn, String refreshToken, StaffDto staff) {

    public static AuthResponse from(AuthResult result) {
        return new AuthResponse(result.accessToken(), result.expiresInSeconds(),
                result.refreshToken(), StaffDto.from(result.staff()));
    }
}
