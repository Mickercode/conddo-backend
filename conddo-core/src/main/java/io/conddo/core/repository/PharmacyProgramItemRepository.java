package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyProgramItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyProgramItemRepository extends JpaRepository<PharmacyProgramItem, UUID> {

    List<PharmacyProgramItem> findByProgramId(UUID programId);

    void deleteByProgramId(UUID programId);
}
