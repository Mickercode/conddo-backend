package io.conddo.studio.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Admin patch payload for {@code PATCH /api/jobs/admin/job-types/{id}}. Every
 * field is optional — null means "leave alone" (PATCH semantics).
 */
public record UpdateJobTypeRequest(
        String displayName,
        String colour,
        List<String> assignedToRoles,
        Integer slaHours,
        Boolean qaRequired,
        List<Map<String, Object>> qaChecklist,
        Boolean active) {
}
