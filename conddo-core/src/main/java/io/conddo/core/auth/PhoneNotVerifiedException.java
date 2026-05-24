package io.conddo.core.auth;

/**
 * Thrown when signup completion is attempted before the phone OTP has been
 * verified. Surfaced as HTTP 409.
 */
public class PhoneNotVerifiedException extends RuntimeException {

    public PhoneNotVerifiedException() {
        super("Phone number has not been verified");
    }
}
