package io.conddo.core.auth;

/**
 * Thrown when an OTP resend is requested too soon (cooldown) or after the
 * per-registration resend cap is reached. Surfaced as HTTP 429.
 */
public class OtpThrottledException extends RuntimeException {

    public OtpThrottledException(String message) {
        super(message);
    }
}
