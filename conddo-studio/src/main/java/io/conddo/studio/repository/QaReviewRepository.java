package io.conddo.studio.repository;

import io.conddo.studio.domain.QaReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QaReviewRepository extends JpaRepository<QaReview, UUID> {

    List<QaReview> findByJobIdOrderByCreatedAtDesc(UUID jobId);

    long countByReviewerIdAndOutcome(UUID reviewerId, String outcome);
}
