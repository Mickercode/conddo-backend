package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Prescription;
import io.conddo.core.domain.Tenant;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.PrescriptionRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped pharmacy prescriptions (PHARMACY_DEEP_DIVE_SPEC §1-5).
 * Every method binds the tenant first so RLS scopes reads + writes.
 *
 * <p>{@code next_refill_due} derivation lives on the entity (so the constructor
 * and PATCH mutators stay correct without service plumbing); the service
 * orchestrates customer lookups, the fill flow, and the SMS reminder pipe.
 */
@Service
public class PrescriptionService {

    /** "Due soon" window — today + 3 days inclusive, per the spec's status buckets. */
    private static final int DUE_SOON_DAYS = 3;
    /** Soft de-dup: never SMS the same customer about the same prescription twice in 12h. */
    private static final Duration REMIND_DEDUP_WINDOW = Duration.ofHours(12);
    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("d MMM");

    private final PrescriptionRepository prescriptionRepository;
    private final CustomerRepository customerRepository;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;
    private final SmsSender smsSender;
    private final Clock clock;

    public PrescriptionService(PrescriptionRepository prescriptionRepository,
                               CustomerRepository customerRepository,
                               TenantRepository tenantRepository,
                               TenantSession tenantSession,
                               SmsSender smsSender, Clock clock) {
        this.prescriptionRepository = prescriptionRepository;
        this.customerRepository = customerRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
        this.smsSender = smsSender;
        this.clock = clock;
    }

    // ----- reads -------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<PrescriptionView> list(String search, String status, UUID customerId, Pageable pageable) {
        tenantSession.bind();
        Page<Prescription> page = prescriptionRepository.findAll(
                buildSpec(search, status, customerId), pageable);
        return page.map(this::view);
    }

    @Transactional(readOnly = true)
    public PrescriptionView get(UUID id) {
        tenantSession.bind();
        return view(require(id));
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        tenantSession.bind();
        LocalDate today = LocalDate.now(clock);
        PrescriptionRepository.SummaryRow row = prescriptionRepository.summary(
                today, today.plusDays(DUE_SOON_DAYS));
        return new Summary(row.total(),
                row.dueSoon() == null ? 0L : row.dueSoon(),
                row.overdue() == null ? 0L : row.overdue(),
                row.oneOff() == null ? 0L : row.oneOff());
    }

    // ----- writes ------------------------------------------------------------

    /**
     * Create from an existing customer OR by name. The spec calls for
     * "customerId xor customerName"; when only a name is given the customer
     * is created on the fly (most pharmacy walk-ins don't have a profile yet).
     */
    @Transactional
    public PrescriptionView create(UUID customerId, String customerName, String customerPhone,
                                   String medication, String dosage, Integer quantity,
                                   Integer refillIntervalDays, String notes) {
        if (medication == null || medication.isBlank()) {
            throw new IllegalArgumentException("medication is required");
        }
        tenantSession.bind();
        UUID resolvedCustomerId = customerId;
        if (resolvedCustomerId == null) {
            if (customerName == null || customerName.isBlank()) {
                throw new IllegalArgumentException("either customerId or customerName is required");
            }
            // Create the customer on the fly. Phone is optional but recommended
            // (no phone = no reminder later).
            Customer fresh = customerRepository.save(
                    new Customer(TenantContext.require(), customerName, null,
                            customerPhone == null || customerPhone.isBlank() ? null : customerPhone,
                            null));
            resolvedCustomerId = fresh.getId();
        } else {
            // Make sure the customer is on this tenant (RLS hides cross-tenant rows).
            customerRepository.findById(resolvedCustomerId)
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
        }
        Prescription saved = prescriptionRepository.save(new Prescription(
                TenantContext.require(), resolvedCustomerId, medication, dosage, quantity,
                refillIntervalDays, notes));
        return view(saved);
    }

    @Transactional
    public PrescriptionView update(UUID id, String medication, String dosage, Integer quantity,
                                   Integer refillIntervalDays, String notes,
                                   boolean refillIntervalProvided) {
        tenantSession.bind();
        Prescription p = require(id);
        p.setMedication(medication);
        if (dosage != null) {
            p.setDosage(dosage);
        }
        if (quantity != null) {
            p.setQuantity(quantity);
        }
        // Refill interval needs the "provided" sentinel because null is a meaningful
        // patch value (= "make this a one-off").
        if (refillIntervalProvided) {
            p.setRefillIntervalDays(refillIntervalDays);
        }
        if (notes != null) {
            p.setNotes(notes);
        }
        return view(prescriptionRepository.save(p));
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        prescriptionRepository.delete(require(id));
    }

    /** {@code POST /prescriptions/{id}/fill} — stamps {@code lastFilledAt} + recomputes due date. */
    @Transactional
    public PrescriptionView fill(UUID id) {
        tenantSession.bind();
        Prescription p = require(id);
        p.markFilled(OffsetDateTime.now(clock));
        return view(prescriptionRepository.save(p));
    }

    /**
     * {@code POST /prescriptions/{id}/remind} — SMS the customer about an
     * upcoming / overdue refill. 422 NoPhoneException if customer has no
     * phone on file. Soft 12-hour de-dup: a recent reminder is a no-op.
     */
    @Transactional
    public void remind(UUID id, String message) {
        tenantSession.bind();
        Prescription p = require(id);
        Customer customer = customerRepository.findById(p.getCustomerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        String phone = customer.getPhone();
        if (phone == null || phone.isBlank()) {
            throw new NoCustomerPhoneException();
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (p.getLastRemindedAt() != null
                && Duration.between(p.getLastRemindedAt(), now).compareTo(REMIND_DEDUP_WINDOW) < 0) {
            // Skip silently — caller still gets 204 and won't spam the customer.
            return;
        }
        String text = (message != null && !message.isBlank())
                ? message
                : defaultReminderText(p, customer);

        smsSender.send(phone, text);
        p.recordReminder(now);
        prescriptionRepository.save(p);
    }

    // ----- view + helpers ----------------------------------------------------

    private PrescriptionView view(Prescription p) {
        Customer customer = customerRepository.findById(p.getCustomerId()).orElse(null);
        return new PrescriptionView(p,
                customer == null ? null : customer.getFullName(),
                customer == null ? null : customer.getPhone());
    }

    private Prescription require(UUID id) {
        return prescriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Prescription not found"));
    }

    private Tenant requireTenant() {
        return tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    /** Default reminder body when caller doesn't supply one. */
    private String defaultReminderText(Prescription p, Customer customer) {
        String firstName = firstName(customer.getFullName());
        String dueLabel = p.getNextRefillDue() == null
                ? "soon"
                : "on " + p.getNextRefillDue().format(HUMAN_DATE);
        String tenantName = requireTenant().getName();
        return "Hi " + firstName + ", your " + p.getMedication() + " refill is due "
                + dueLabel + ". Reply to confirm a pickup time. — " + tenantName;
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        int sp = fullName.indexOf(' ');
        return sp > 0 ? fullName.substring(0, sp) : fullName;
    }

    /** Builds the JPA Specification for the list endpoint's search + status + customer filters. */
    private Specification<Prescription> buildSpec(String search, String status, UUID customerId) {
        LocalDate today = LocalDate.now(clock);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(root.get("medication")), pattern));
            }
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (status != null && !status.isBlank()) {
                switch (status.trim().toLowerCase()) {
                    case "active":
                        predicates.add(cb.isNotNull(root.get("refillIntervalDays")));
                        predicates.add(cb.or(
                                cb.isNull(root.get("nextRefillDue")),
                                cb.greaterThan(root.get("nextRefillDue"), today.plusDays(DUE_SOON_DAYS))));
                        break;
                    case "due_soon":
                        predicates.add(cb.between(root.get("nextRefillDue"),
                                today, today.plusDays(DUE_SOON_DAYS)));
                        break;
                    case "overdue":
                        predicates.add(cb.lessThan(root.get("nextRefillDue"), today));
                        break;
                    case "one_off":
                        predicates.add(cb.isNull(root.get("refillIntervalDays")));
                        break;
                    default:
                        // unknown bucket — leave as no-op so the FE never gets a 400 over a typo
                }
            }
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.orderBy(cb.desc(root.get("issuedAt")));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // ----- view / summary records --------------------------------------------

    /** The full wire shape — matches `conddo-app/lib/api/prescriptions.ts` exactly. */
    public record PrescriptionView(Prescription prescription, String customerName, String customerPhone) {
    }

    public record Summary(long total, long dueSoon, long overdue, long oneOff) {
    }

    /** Customer has no phone on file — controller maps to 422 with code {@code no_customer_phone}. */
    public static class NoCustomerPhoneException extends RuntimeException {
        public NoCustomerPhoneException() {
            super("Customer has no phone on file.");
        }
    }
}
