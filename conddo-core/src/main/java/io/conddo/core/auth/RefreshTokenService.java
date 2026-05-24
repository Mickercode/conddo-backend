package io.conddo.core.auth;

import io.conddo.core.domain.RefreshToken;
import io.conddo.core.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Issues, rotates, and revokes refresh tokens in the opaque "selector.verifier"
 * scheme (PRD §6.2).
 *
 * <p>Security model:
 * <ul>
 *   <li><b>Rotation</b> — every successful refresh mints a new token and revokes
 *       the one presented; a client should only ever hold the latest.</li>
 *   <li><b>Family</b> — all tokens descended from one login share a {@code familyId}.</li>
 *   <li><b>Reuse detection</b> — presenting a token that was already revoked
 *       (rotated away or logged out) means it leaked, so the entire family is
 *       revoked, forcing re-authentication.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final RefreshTokenReuseGuard reuseGuard;
    private final AuthProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, PasswordHasher passwordHasher,
                               RefreshTokenReuseGuard reuseGuard, AuthProperties properties, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordHasher = passwordHasher;
        this.reuseGuard = reuseGuard;
        this.properties = properties;
        this.clock = clock;
    }

    /** Issues the first refresh token of a brand-new family (one login session). */
    public String issue(UUID userId, UUID tenantId) {
        return mint(userId, tenantId, UUID.randomUUID()).rawToken();
    }

    /**
     * Validates the presented token and rotates it: the old token is revoked and
     * a new one is minted within the same family. Detects reuse of a revoked
     * token and, in that case, revokes the whole family before failing.
     */
    public Rotation rotate(String rawToken) {
        RefreshToken current = lookup(rawToken);
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (current.isRevoked()) {
            log.warn("Refresh-token reuse detected (family {}, tenant {}); revoking family.",
                    current.getFamilyId(), current.getTenantId());
            reuseGuard.revokeFamily(current.getFamilyId(), "reuse-detected");
            throw new InvalidRefreshTokenException();
        }
        if (current.isExpired(now)) {
            throw new InvalidRefreshTokenException();
        }

        Minted next = mint(current.getUserId(), current.getTenantId(), current.getFamilyId());
        current.revoke(now, "rotated");
        current.replacedBy(next.entity().getId());
        refreshTokenRepository.save(current);
        return new Rotation(current.getUserId(), current.getTenantId(), next.rawToken());
    }

    /** Revokes every refresh token a user holds — e.g. after a password reset. */
    public void revokeAllForUser(UUID userId, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RefreshToken> tokens = refreshTokenRepository.findByUserId(userId);
        tokens.forEach(token -> token.revoke(now, reason));
        refreshTokenRepository.saveAll(tokens);
    }

    /** Best-effort logout: revoke the presented token's whole family. Never throws. */
    public void revokeFamilyOf(String rawToken) {
        try {
            RefreshToken token = lookup(rawToken);
            reuseGuard.revokeFamily(token.getFamilyId(), "logout");
        } catch (InvalidRefreshTokenException ignored) {
            // Missing/malformed/unknown token — logout is idempotent.
        }
    }

    /** Parses and resolves a raw token, verifying the secret half. */
    private RefreshToken lookup(String rawToken) {
        if (rawToken == null) {
            throw new InvalidRefreshTokenException();
        }
        int sep = rawToken.indexOf(OpaqueToken.SEPARATOR);
        if (sep <= 0 || sep == rawToken.length() - 1) {
            throw new InvalidRefreshTokenException();
        }
        String selector = rawToken.substring(0, sep);
        String verifier = rawToken.substring(sep + 1);

        RefreshToken token = refreshTokenRepository.findBySelector(selector)
                .orElseThrow(InvalidRefreshTokenException::new);
        if (!passwordHasher.matches(verifier, token.getTokenHash())) {
            // Selector matched but the secret did not — treat as compromise.
            log.warn("Refresh-token verifier mismatch (family {}); revoking family.", token.getFamilyId());
            reuseGuard.revokeFamily(token.getFamilyId(), "verifier-mismatch");
            throw new InvalidRefreshTokenException();
        }
        return token;
    }

    private Minted mint(UUID userId, UUID tenantId, UUID familyId) {
        String selector = OpaqueToken.randomBase64Url(OpaqueToken.SELECTOR_BYTES);
        String verifier = OpaqueToken.randomBase64Url(OpaqueToken.VERIFIER_BYTES);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(properties.refreshTokenTtl());
        RefreshToken token = new RefreshToken(userId, tenantId, familyId, selector,
                passwordHasher.hash(verifier), expiresAt);
        refreshTokenRepository.save(token);
        return new Minted(token, selector + OpaqueToken.SEPARATOR + verifier);
    }

    private record Minted(RefreshToken entity, String rawToken) {
    }

    /** The result of a rotation: who it belongs to, and the new raw token to re-cookie. */
    public record Rotation(UUID userId, UUID tenantId, String rawToken) {
    }
}
