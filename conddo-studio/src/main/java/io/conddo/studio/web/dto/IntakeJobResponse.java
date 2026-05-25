package io.conddo.studio.web.dto;

import io.conddo.studio.domain.Job;

import java.util.UUID;

/** The created job handle returned to the platform: enough to link + track it. */
public record IntakeJobResponse(UUID id, String jobNumber, String status) {

    public static IntakeJobResponse from(Job job) {
        return new IntakeJobResponse(job.getId(), job.getJobNumber(), job.getStatus());
    }
}
