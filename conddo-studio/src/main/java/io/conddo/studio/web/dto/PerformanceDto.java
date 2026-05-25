package io.conddo.studio.web.dto;

import io.conddo.studio.jobs.JobService.Performance;

import java.util.UUID;

/** A staff member's performance snapshot. */
public record PerformanceDto(
        UUID staffId,
        long jobsCompleted,
        int jobsTarget,
        double firstPassQaRate,
        long revisionsReceived
) {
    public static PerformanceDto from(Performance p) {
        return new PerformanceDto(p.staffId(), p.jobsCompleted(), p.jobsTarget(),
                p.firstPassQaRate(), p.revisionsReceived());
    }
}
