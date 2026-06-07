package io.conddo.core.repository;

import io.conddo.core.domain.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** RLS-scoped. Pending first then by created desc. */
public interface ConsultationRepository extends JpaRepository<Consultation, UUID> {

    List<Consultation> findAllByOrderByStatusAscCreatedAtDesc();

    List<Consultation> findByStatusOrderByCreatedAtDesc(String status);
}
