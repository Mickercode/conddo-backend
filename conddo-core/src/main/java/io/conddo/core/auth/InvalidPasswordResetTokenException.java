package io.conddo.core.auth;

/**
 * Thrown when a password-reset token is missing, malformed, unknown, already
 * used, or expired. Surfaced as HTTP 400.
 */
public class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException() {
        super("Invalid or expired password reset token");
    }
}
