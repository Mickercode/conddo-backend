package io.conddo.core.common;

/**
 * Thrown when a requested resource does not exist (or is not visible to the
 * current tenant under RLS). Surfaced as HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
