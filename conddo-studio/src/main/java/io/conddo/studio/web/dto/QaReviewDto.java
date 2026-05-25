package io.conddo.studio.web.dto;

import io.conddo.studio.domain.QaReview;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** A QA review record. */
public record QaReviewDto(
        UUID id,
        String outcome,
        Map<String, Object> checklist,
        String reviewerNotes,
        String positiveNotes,
        OffsetDateTime at
) {
    public static QaReviewDto from(QaReview r) {
        return new QaReviewDto(r.getId(), r.getOutcome(), r.getChecklist(),
                r.getReviewerNotes(), r.getPositiveNotes(), r.getCreatedAt());
    }
}
