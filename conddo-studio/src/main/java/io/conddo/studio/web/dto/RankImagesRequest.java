package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for {@code POST /api/jobs/{id}/rank-images} — rate a set of candidate
 * images for use in the job's website. {@code sectionType} (e.g. {@code "hero"})
 * is optional; defaults to {@code "hero"} if omitted.
 */
public record RankImagesRequest(@NotEmpty List<String> imageUrls, String sectionType) {
}
