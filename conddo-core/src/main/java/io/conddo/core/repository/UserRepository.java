package io.conddo.core.repository;

import io.conddo.core.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * As with {@code CustomerRepository}, there is no {@code findByTenantId(...)}:
 * RLS scopes every query to the bound tenant. {@code email} is unique only
 * <em>per tenant</em>, so {@link #findByEmail} returns at most one row once a
 * tenant is bound to the transaction.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
}
