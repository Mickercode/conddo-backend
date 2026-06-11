package io.conddo.core.repository;

import io.conddo.core.domain.PosSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosSaleItemRepository extends JpaRepository<PosSaleItem, UUID> {

    List<PosSaleItem> findBySaleIdOrderByCreatedAtAsc(UUID saleId);

    Optional<PosSaleItem> findBySaleIdAndProductId(UUID saleId, UUID productId);
}
