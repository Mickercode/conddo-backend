package io.conddo.api.web.dto;

/**
 * Create/update a pipeline stage (§11.4). On create, {@code name} is required;
 * on update, both are optional (only sent fields change). {@code position}
 * orders the stage in the pipeline.
 */
public record StageRequest(String name, Integer position) {
}
