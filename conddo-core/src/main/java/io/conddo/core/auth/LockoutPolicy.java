package io.conddo.core.auth;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * The account-lockout policy (PRD §6.2), shared by both auth axes: after
 * {@code lockoutThreshold} consecutive failures the account locks, and the
 * lockout window doubles with each further failure (15m, 30m, 1h, …), capped.
 */
@Component
public class LockoutPolicy {

    private static final int MAX_BACKOFF_EXPONENT = 6; // cap at 2^6 × base

    private final AuthProperties properties;

    public LockoutPolicy(AuthProperties properties) {
        this.properties = properties;
    }

    /** Records a failed attempt on the account and locks it once the threshold is hit. */
    public void registerFailedAttempt(LockableAccount account, OffsetDateTime now) {
        account.recordFailedLogin();
        int over = account.getFailedLoginAttempts() - properties.lockoutThreshold();
        if (over >= 0) {
            long multiplier = 1L << Math.min(over, MAX_BACKOFF_EXPONENT);
            account.lockUntil(now.plus(properties.lockoutBaseDuration().multipliedBy(multiplier)));
        }
    }
}
