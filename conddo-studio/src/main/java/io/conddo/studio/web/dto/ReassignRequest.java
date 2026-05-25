package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Reassign a job to a different staff member. */
public record ReassignRequest(@NotNull UUID staffId) {
}
