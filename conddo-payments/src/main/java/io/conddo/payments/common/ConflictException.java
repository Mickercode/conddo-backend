package io.conddo.payments.common;

/** Duplicate reference / state-conflict — maps to 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
