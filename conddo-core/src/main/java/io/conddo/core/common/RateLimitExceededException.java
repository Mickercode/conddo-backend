package io.conddo.core.common;

/** Thrown when a caller exceeds a rate limit (→ HTTP 429). Used by public endpoints. */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
