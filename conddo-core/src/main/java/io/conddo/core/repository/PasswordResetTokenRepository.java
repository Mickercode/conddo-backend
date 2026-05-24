package io.conddo.core.repository;

import io.conddo.core.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Looked up by the unguessable {@code selector} when a user follows a reset
 * link. Not RLS-scoped — consumed unauthenticated (see V4__auth_grants.sql).
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findBySelector(String selector);
}
