package io.conddo.studio.repository;

import io.conddo.studio.domain.JobType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobTypeRepository extends JpaRepository<JobType, String> {
}
