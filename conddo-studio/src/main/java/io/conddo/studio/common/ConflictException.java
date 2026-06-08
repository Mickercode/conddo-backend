package io.conddo.studio.common;

/**
 * Maps to HTTP 409 — an invalid state transition or a duplicate (e.g.
 * claiming a taken job). Defaults to error code {@code "CONFLICT"} in the
 * exception-handler envelope; callers needing a more specific code (e.g.
 * {@code "SUBDOMAIN_TAKEN"} for the site-admin flow) pass it as the
 * first arg.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        this("CONFLICT", message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "CONFLICT" : code;
    }

    public String getCode() {
        return code;
    }
}
