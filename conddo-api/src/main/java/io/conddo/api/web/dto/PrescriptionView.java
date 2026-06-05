package io.conddo.api.web.dto;

import io.conddo.core.service.PrescriptionService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape matching {@code conddo-app/lib/api/prescriptions.ts} verbatim.
 * Field names + types are FE-binding — never rename without coordinating.
 */
public record PrescriptionView(
        UUID id,
        UUID customerId,
        String customerName,
        String customerPhone,
        String medication,
        String dosage,
        Integer quantity,
        String notes,
        OffsetDateTime issuedAt,
        OffsetDateTime lastFilledAt,
        Integer refillIntervalDays,
        LocalDate nextRefillDue) {

    public static PrescriptionView from(PrescriptionService.PrescriptionView v) {
        return new PrescriptionView(
                v.prescription().getId(),
                v.prescription().getCustomerId(),
                v.customerName(),
                v.customerPhone(),
                v.prescription().getMedication(),
                v.prescription().getDosage(),
                v.prescription().getQuantity(),
                v.prescription().getNotes(),
                v.prescription().getIssuedAt(),
                v.prescription().getLastFilledAt(),
                v.prescription().getRefillIntervalDays(),
                v.prescription().getNextRefillDue());
    }
}
