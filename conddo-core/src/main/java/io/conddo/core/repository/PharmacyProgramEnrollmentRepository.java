package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyProgramEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyProgramEnrollmentRepository
        extends JpaRepository<PharmacyProgramEnrollment, UUID> {

    List<PharmacyProgramEnrollment> findByProgramIdOrderByEnrolledAtDesc(UUID programId);

    long countByProgramIdAndStatus(UUID programId, String status);
}
