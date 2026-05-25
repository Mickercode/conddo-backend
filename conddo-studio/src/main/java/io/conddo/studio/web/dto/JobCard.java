package io.conddo.studio.web.dto;

import io.conddo.studio.domain.Job;
import io.conddo.studio.jobs.JobService.JobView;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A job board card / list row. {@code slaTone} is GREEN/AMBER/RED. */
public record JobCard(
        UUID id,
        String jobNumber,
        String jobType,
        String title,
        String status,
        String slaTone,
        OffsetDateTime slaDeadline,
        UUID assignedTo,
        UUID tenantId,
        OffsetDateTime createdAt
) {
    public static JobCard from(JobView v) {
        Job j = v.job();
        return new JobCard(j.getId(), j.getJobNumber(), j.getJobTypeId(), j.getTitle(), j.getStatus(),
                v.slaTone(), j.getSlaDeadline(), j.getAssignedTo(), j.getTenantId(), j.getCreatedAt());
    }
}
