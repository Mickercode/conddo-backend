package io.conddo.api.web.dto;

import io.conddo.core.service.PrescriptionService;

/** Dashboard tile counts. */
public record PrescriptionSummary(long total, long dueSoon, long overdue, long oneOff) {

    public static PrescriptionSummary from(PrescriptionService.Summary s) {
        return new PrescriptionSummary(s.total(), s.dueSoon(), s.overdue(), s.oneOff());
    }
}
