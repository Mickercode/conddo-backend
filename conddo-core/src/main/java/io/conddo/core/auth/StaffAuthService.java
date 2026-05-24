package io.conddo.core.auth;

import io.conddo.core.domain.StaffUser;
import io.conddo.core.repository.StaffUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Authenticates internal Handel Cores staff (PRD v1.3 §22). Mirrors tenant login
 * (BCrypt, shared {@link LockoutPolicy}, identical failure semantics) but against
 * the global, non-RLS {@code staff_users} table — so there is no tenant to
 * resolve or bind. The issued token carries no tenant_id.
 *
 * <p>Phase 1 scope: access token only. Staff refresh tokens are deferred to
 * Phase 3 (Conddo Studio), when staff use the tool continuously.
 */
@Service
public class StaffAuthService {

    private final StaffUserRepository staffUserRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final LockoutPolicy lockoutPolicy;
    private final Clock clock;

    private final String timingEqualiserHash;

    public StaffAuthService(StaffUserRepository staffUserRepository, PasswordHasher passwordHasher,
                            JwtService jwtService, LockoutPolicy lockoutPolicy, Clock clock) {
        this.staffUserRepository = staffUserRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.lockoutPolicy = lockoutPolicy;
        this.clock = clock;
        this.timingEqualiserHash = passwordHasher.hash("timing-equaliser");
    }

    // noRollbackFor — keep the incremented failed-login counter / lockout on a
    // failed attempt even though we throw (see AuthService.login).
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public StaffAuthResult login(String email, String rawPassword) {
        StaffUser staff = staffUserRepository.findByEmail(email).orElse(null);
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (staff == null) {
            passwordHasher.matches(rawPassword, timingEqualiserHash);
            throw new InvalidCredentialsException();
        }
        if (staff.isLocked(now)) {
            throw new AccountLockedException(staff.getLockedUntil());
        }
        if (!staff.isActive() || !passwordHasher.matches(rawPassword, staff.getPasswordHash())) {
            if (staff.isActive()) {
                lockoutPolicy.registerFailedAttempt(staff, now);
                staffUserRepository.save(staff);
            }
            throw new InvalidCredentialsException();
        }

        staff.recordSuccessfulLogin(now);
        staffUserRepository.save(staff);
        String accessToken = jwtService.issueStaffAccessToken(staff.getId(), staff.getInternalRole());
        return new StaffAuthResult(accessToken, jwtService.accessTokenTtl(), staff.getId(), staff.getInternalRole());
    }
}
