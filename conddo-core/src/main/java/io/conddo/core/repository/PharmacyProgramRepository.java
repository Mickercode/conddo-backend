package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyProgram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyProgramRepository extends JpaRepository<PharmacyProgram, UUID> {

    List<PharmacyProgram> findAllByOrderByCreatedAtDesc();

    List<PharmacyProgram> findByPublishedTrueAndActiveTrueOrderByCreatedAtDesc();
}
