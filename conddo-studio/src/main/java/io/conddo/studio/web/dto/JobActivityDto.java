package io.conddo.studio.web.dto;

import io.conddo.studio.domain.JobActivity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An entry in a job's activity timeline. */
public record JobActivityDto(UUID id, String action, String detail, UUID staffId, OffsetDateTime at) {

    public static JobActivityDto from(JobActivity a) {
        return new JobActivityDto(a.getId(), a.getAction(), a.getDetail(), a.getStaffId(), a.getCreatedAt());
    }
}
