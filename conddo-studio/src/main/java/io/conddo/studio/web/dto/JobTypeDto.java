package io.conddo.studio.web.dto;

import io.conddo.studio.domain.JobType;

import java.util.List;
import java.util.Map;

/** Wire shape for the admin job-type CRUD endpoints. */
public record JobTypeDto(String id, String displayName, String colour, List<String> assignedToRoles,
                         int slaHours, boolean qaRequired, List<Map<String, Object>> qaChecklist,
                         boolean active) {

    public static JobTypeDto from(JobType type) {
        return new JobTypeDto(type.getId(), type.getDisplayName(), type.getColour(),
                type.getAssignedToRoles(), type.getSlaHours(), type.isQaRequired(),
                type.getQaChecklist(), type.isActive());
    }
}
