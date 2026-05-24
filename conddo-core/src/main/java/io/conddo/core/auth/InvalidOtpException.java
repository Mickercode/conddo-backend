package io.conddo.core.auth;

/**
 * Thrown when a signup OTP is wrong, expired, or has had too many failed
 * attempts (the code is then dead and a resend is required). Surfaced as 400.
 */
public class InvalidOtpException extends RuntimeException {

    public InvalidOtpException() {
        super("Invalid or expired verification code");
    }
}
