package io.conddo.core.auth;

import java.time.OffsetDateTime;

/**
 * Thrown when a login is attempted against an account that is currently locked
 * out after too many failures (PRD §6.2). Surfaced as HTTP 423.
 */
public class AccountLockedException extends RuntimeException {

    private final transient OffsetDateTime lockedUntil;

    public AccountLockedException(OffsetDateTime lockedUntil) {
        super("Account is temporarily locked due to too many failed login attempts");
        this.lockedUntil = lockedUntil;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }
}
