package io.conddo.core.repository;

import io.conddo.core.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lookups are by the unguessable {@code selector} (never enumerated) or by
 * {@code familyId} for reuse-driven family revocation. Not RLS-scoped — these
 * run on unauthenticated refresh requests (see V4__auth_grants.sql).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findBySelector(String selector);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByUserId(UUID userId);
}
