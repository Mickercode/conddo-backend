package io.conddo.core.repository;

import io.conddo.core.domain.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Looked up by the registration's unguessable UUID id (carried by the frontend
 * through the signup wizard). Not RLS-scoped — registrations are pre-tenant.
 */
public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, UUID> {
}
