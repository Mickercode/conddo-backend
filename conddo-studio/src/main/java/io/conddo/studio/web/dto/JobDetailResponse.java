package io.conddo.studio.web.dto;

import io.conddo.studio.domain.Job;
import io.conddo.studio.jobs.JobService.JobDetail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Full job detail for the board: header, brief, assets, activity timeline, and QA history. */
public record JobDetailResponse(
        UUID id,
        String jobNumber,
        String jobType,
        String title,
        String status,
        String slaTone,
        OffsetDateTime slaDeadline,
        int slaExtendedBy,
        int revisionCount,
        UUID assignedTo,
        String assignedToName,
        UUID tenantId,
        String studioUrl,
        Map<String, Object> brief,
        List<Map<String, Object>> assets,
        List<JobActivityDto> activity,
        List<QaReviewDto> qaReviews,
        OffsetDateTime createdAt
) {
    public static JobDetailResponse from(JobDetail d) {
        Job j = d.job();
        return new JobDetailResponse(j.getId(), j.getJobNumber(), j.getJobTypeId(), j.getTitle(), j.getStatus(),
                d.slaTone(), j.getSlaDeadline(), j.getSlaExtendedBy(), j.getRevisionCount(),
                j.getAssignedTo(), d.assignedToName(), j.getTenantId(), j.getStudioUrl(),
                j.getBrief(), j.getAssets(),
                d.activity().stream().map(JobActivityDto::from).toList(),
                d.qaReviews().stream().map(QaReviewDto::from).toList(),
                j.getCreatedAt());
    }
}
