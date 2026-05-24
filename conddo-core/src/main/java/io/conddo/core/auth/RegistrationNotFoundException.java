package io.conddo.core.auth;

/**
 * Thrown when a signup registration id is unknown or already completed.
 * Surfaced as HTTP 404.
 */
public class RegistrationNotFoundException extends RuntimeException {

    public RegistrationNotFoundException() {
        super("Registration not found or already completed");
    }
}
