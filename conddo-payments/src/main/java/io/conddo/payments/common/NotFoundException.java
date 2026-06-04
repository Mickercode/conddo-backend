package io.conddo.payments.common;

/** Anything missing — payment, tenant account, … — maps to 404 in the handler. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
