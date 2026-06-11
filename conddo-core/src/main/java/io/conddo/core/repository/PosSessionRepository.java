package io.conddo.core.repository;

import io.conddo.core.domain.PosSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PosSessionRepository extends JpaRepository<PosSession, UUID> {

    /** The cashier's currently OPEN session, if any. RLS-scoped. */
    Optional<PosSession> findFirstByCashierIdAndStatus(UUID cashierId, String status);
}
