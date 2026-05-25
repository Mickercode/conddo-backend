package io.conddo.studio.common;

/** Maps to HTTP 401 — bad staff login. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
