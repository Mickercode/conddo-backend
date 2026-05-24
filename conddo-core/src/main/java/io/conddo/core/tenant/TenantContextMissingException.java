package io.conddo.core.tenant;

/**
 * Thrown when an operation that requires a tenant runs without one in context.
 * Surfaced to clients as HTTP 400 (see GlobalExceptionHandler).
 */
public class TenantContextMissingException extends RuntimeException {

    public TenantContextMissingException() {
        super("No tenant in context — the request carries no resolvable tenant.");
    }
}
