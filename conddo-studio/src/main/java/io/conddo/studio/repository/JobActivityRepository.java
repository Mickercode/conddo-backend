package io.conddo.studio.repository;

import io.conddo.studio.domain.JobActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobActivityRepository extends JpaRepository<JobActivity, UUID> {

    List<JobActivity> findByJobIdOrderByCreatedAtDesc(UUID jobId);
}
