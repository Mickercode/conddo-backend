package io.conddo.studio.repository;

import io.conddo.studio.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByStatusInOrderBySlaDeadlineAsc(Collection<String> statuses);

    List<Job> findByAssignedToAndStatusInOrderBySlaDeadlineAsc(UUID assignedTo, Collection<String> statuses);

    List<Job> findAllByOrderBySlaDeadlineAsc();

    long countByAssignedToAndStatus(UUID assignedTo, String status);

    long countByAssignedToAndStatusIn(UUID assignedTo, Collection<String> statuses);
}
