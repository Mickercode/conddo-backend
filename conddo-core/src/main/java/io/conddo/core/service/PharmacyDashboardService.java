package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Consultation;
import io.conddo.core.domain.CustomerPrescription;
import io.conddo.core.domain.User;
import io.conddo.core.repository.ConsultationRepository;
import io.conddo.core.repository.CustomerPrescriptionRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pharmacy review queues for the dashboard
 * (PHARMACY_PUBLIC_API_SPEC §12, HANDOFF_2026-06-07 bugs 1+2). Both
 * tables are populated by inbound customer submissions from the
 * merchant's public website — those public-side endpoints live in the
 * larger PHARMACY_PUBLIC_API_SPEC slice. This service exposes only the
 * dashboard reads + status mutations the pharmacist works through.
 */
@Service
public class PharmacyDashboardService {

    private final CustomerPrescriptionRepository customerPrescriptionRepository;
    private final ConsultationRepository consultationRepository;
    private final UserRepository userRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PharmacyDashboardService(CustomerPrescriptionRepository customerPrescriptionRepository,
                                    ConsultationRepository consultationRepository,
                                    UserRepository userRepository,
                                    TenantSession tenantSession,
                                    Clock clock) {
        this.customerPrescriptionRepository = customerPrescriptionRepository;
        this.consultationRepository = consultationRepository;
        this.userRepository = userRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    // ----- customer-prescriptions -------------------------------------------

    @Transactional(readOnly = true)
    public List<CustomerPrescription> listCustomerPrescriptions(String status) {
        tenantSession.bind();
        if (status == null || status.isBlank()) {
            return customerPrescriptionRepository.findAllByOrderByStatusAscSubmittedAtDesc();
        }
        return customerPrescriptionRepository.findByStatusOrderBySubmittedAtDesc(status.toUpperCase());
    }

    @Transactional(readOnly = true)
    public CustomerPrescription getCustomerPrescription(UUID id) {
        tenantSession.bind();
        return customerPrescriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer prescription not found"));
    }

    @Transactional
    public CustomerPrescription reviewCustomerPrescription(UUID id, String newStatus,
                                                           String reviewNote, UUID reviewerId) {
        tenantSession.bind();
        CustomerPrescription row = customerPrescriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer prescription not found"));
        String reviewerName = null;
        if (reviewerId != null) {
            Optional<User> reviewer = userRepository.findById(reviewerId);
            if (reviewer.isPresent()) {
                // Some accounts (esp. signup-time admins) have no fullName set —
                // fall back to email so the FE never renders "null reviewed this".
                String fullName = reviewer.get().getFullName();
                reviewerName = (fullName != null && !fullName.isBlank())
                        ? fullName : reviewer.get().getEmail();
            }
        }
        row.review(newStatus, reviewNote, reviewerId, reviewerName, OffsetDateTime.now(clock));
        return customerPrescriptionRepository.save(row);
    }

    // ----- consultations -----------------------------------------------------

    @Transactional(readOnly = true)
    public List<Consultation> listConsultations(String status) {
        tenantSession.bind();
        if (status == null || status.isBlank()) {
            return consultationRepository.findAllByOrderByStatusAscCreatedAtDesc();
        }
        return consultationRepository.findByStatusOrderByCreatedAtDesc(status.toUpperCase());
    }

    @Transactional(readOnly = true)
    public Consultation getConsultation(UUID id) {
        tenantSession.bind();
        return consultationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Consultation not found"));
    }

    @Transactional
    public Consultation updateConsultationStatus(UUID id, String newStatus, String pharmacistNote) {
        tenantSession.bind();
        Consultation row = consultationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Consultation not found"));
        row.updateStatus(newStatus, pharmacistNote, OffsetDateTime.now(clock));
        return consultationRepository.save(row);
    }
}
