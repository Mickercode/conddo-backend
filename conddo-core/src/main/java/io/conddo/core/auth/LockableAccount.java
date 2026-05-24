package io.conddo.core.auth;

import java.time.OffsetDateTime;

/**
 * An account that can be locked out after repeated failed logins. Implemented by
 * both {@code User} (tenant axis) and {@code StaffUser} (internal axis) so the
 * lockout policy can be applied uniformly via {@link LockoutPolicy}.
 */
public interface LockableAccount {

    int getFailedLoginAttempts();

    void recordFailedLogin();

    void lockUntil(OffsetDateTime until);

    boolean isLocked(OffsetDateTime now);

    OffsetDateTime getLockedUntil();
}
