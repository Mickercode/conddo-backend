package io.conddo.core.auth;

/**
 * Thrown when a refresh token is missing, malformed, unknown, expired, or has
 * been revoked (including reuse-after-rotation, which also kills the family).
 * Surfaced uniformly as HTTP 401 so it reveals nothing about why.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token");
    }
}
