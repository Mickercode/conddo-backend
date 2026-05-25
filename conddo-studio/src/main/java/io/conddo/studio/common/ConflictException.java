package io.conddo.studio.common;

/** Maps to HTTP 409 — an invalid state transition or a duplicate (e.g. claiming a taken job). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
