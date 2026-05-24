package io.conddo.core.auth;

import io.conddo.core.domain.RefreshToken;
import io.conddo.core.repository.RefreshTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Revokes an entire refresh-token family in its <em>own</em> committed
 * transaction ({@code REQUIRES_NEW}).
 *
 * <p>This exists because reuse detection both has a side effect we must keep
 * (revoke the family) and then rejects the request by throwing — which would
 * roll back the surrounding transaction, undoing the revocation. Committing in a
 * separate transaction is what makes reuse detection actually invalidate the
 * session. It is a separate bean so the call goes through the Spring proxy
 * (self-invocation would not honour {@code REQUIRES_NEW}).
 */
@Component
public class RefreshTokenReuseGuard {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public RefreshTokenReuseGuard(RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
        family.forEach(token -> token.revoke(now, reason));
        refreshTokenRepository.saveAll(family);
    }
}
