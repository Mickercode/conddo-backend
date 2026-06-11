package io.conddo.core.repository;

import io.conddo.core.domain.PosSale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PosSaleRepository extends JpaRepository<PosSale, UUID> {

    List<PosSale> findBySessionId(UUID sessionId);

    long countBySessionIdAndStatus(UUID sessionId, String status);
}
