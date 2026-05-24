package io.conddo.core.auth;

/**
 * Thrown when login fails for any reason that must look identical to the client
 * — unknown tenant, unknown email, wrong password, or inactive account — to
 * avoid leaking which accounts exist. Surfaced as HTTP 401.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
