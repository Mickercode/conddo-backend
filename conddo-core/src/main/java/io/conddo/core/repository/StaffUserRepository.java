package io.conddo.core.repository;

import io.conddo.core.domain.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Staff are not tenant-scoped, so {@code staff_users} has no RLS and email is
 * globally unique — {@link #findByEmail} resolves a staff member directly.
 */
public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {

    Optional<StaffUser> findByEmail(String email);
}
