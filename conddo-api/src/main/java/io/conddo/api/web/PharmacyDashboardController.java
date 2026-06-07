package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Consultation;
import io.conddo.core.domain.CustomerPrescription;
import io.conddo.core.service.PharmacyDashboardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pharmacy dashboard review queues (HANDOFF_2026-06-07 bugs 1+2). The FE
 * pharmacist UI shipped at these exact paths — keep the wire shape in
 * lockstep with {@code conddo-app/lib/api/pharmacyDashboard.ts}.
 *
 * <p>Tenant scoping comes from the JWT and is enforced by RLS. Both reads
 * and mutations are open to TENANT_ADMIN + STAFF (the pharmacist on staff
 * is who actually reviews the queues).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class PharmacyDashboardController {

    private final PharmacyDashboardService service;

    public PharmacyDashboardController(PharmacyDashboardService service) {
        this.service = service;
    }

    // ----- customer-prescriptions -------------------------------------------

    @GetMapping("/customer-prescriptions")
    public ApiResponse<List<CustomerPrescriptionResponse>> listPrescriptions(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listCustomerPrescriptions(status).stream()
                .map(PharmacyDashboardController::toPrescription)
                .toList());
    }

    @GetMapping("/customer-prescriptions/{id}")
    public ApiResponse<CustomerPrescriptionResponse> getPrescription(@PathVariable UUID id) {
        return ApiResponse.ok(toPrescription(service.getCustomerPrescription(id)));
    }

    @PatchMapping("/customer-prescriptions/{id}/review")
    public ApiResponse<CustomerPrescriptionResponse> review(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID reviewerId = jwt == null ? null : UUID.fromString(jwt.getSubject());
        return ApiResponse.ok(toPrescription(
                service.reviewCustomerPrescription(id, body.status(), body.reviewNote(), reviewerId)));
    }

    // ----- consultations -----------------------------------------------------

    @GetMapping("/consultations")
    public ApiResponse<List<ConsultationResponse>> listConsultations(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listConsultations(status).stream()
                .map(PharmacyDashboardController::toConsultation)
                .toList());
    }

    @GetMapping("/consultations/{id}")
    public ApiResponse<ConsultationResponse> getConsultation(@PathVariable UUID id) {
        return ApiResponse.ok(toConsultation(service.getConsultation(id)));
    }

    @PatchMapping("/consultations/{id}/status")
    public ApiResponse<ConsultationResponse> updateConsultationStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateConsultationStatusRequest body) {
        return ApiResponse.ok(toConsultation(
                service.updateConsultationStatus(id, body.status(), body.pharmacistNote())));
    }

    // ----- DTOs --------------------------------------------------------------

    public record ReviewRequest(@NotBlank String status, String reviewNote) {
    }

    public record UpdateConsultationStatusRequest(@NotBlank String status, String pharmacistNote) {
    }

    public record CustomerPrescriptionResponse(
            UUID id, UUID customerId, String customerName, String customerPhone,
            String fileUrl, String patientName, String prescriberName, String notes,
            String status, String reviewNote, OffsetDateTime reviewedAt, String reviewedByName,
            UUID orderId, OffsetDateTime submittedAt) {
    }

    public record ConsultationResponse(
            UUID id, UUID customerId, String customerName, String whatsappNumber,
            String topic, String preferredTime, String status, String pharmacistNote,
            OffsetDateTime createdAt, OffsetDateTime completedAt) {
    }

    private static CustomerPrescriptionResponse toPrescription(CustomerPrescription p) {
        return new CustomerPrescriptionResponse(
                p.getId(), p.getCustomerId(), p.getCustomerName(), p.getCustomerPhone(),
                p.getFileUrl(), p.getPatientName(), p.getPrescriberName(), p.getNotes(),
                p.getStatus(), p.getReviewNote(), p.getReviewedAt(), p.getReviewedByName(),
                p.getOrderId(), p.getSubmittedAt());
    }

    private static ConsultationResponse toConsultation(Consultation c) {
        return new ConsultationResponse(
                c.getId(), c.getCustomerId(), c.getCustomerName(), c.getWhatsappNumber(),
                c.getTopic(), c.getPreferredTime(), c.getStatus(), c.getPharmacistNote(),
                c.getCreatedAt(), c.getCompletedAt());
    }
}
