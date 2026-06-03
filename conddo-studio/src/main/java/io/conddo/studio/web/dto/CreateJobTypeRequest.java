package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

/** Admin create payload for {@code POST /api/jobs/admin/job-types}. */
public record CreateJobTypeRequest(
        @NotBlank String id,
        @NotBlank String displayName,
        String colour,
        List<String> assignedToRoles,
        @Positive int slaHours,
        Boolean qaRequired,
        List<Map<String, Object>> qaChecklist) {
}
