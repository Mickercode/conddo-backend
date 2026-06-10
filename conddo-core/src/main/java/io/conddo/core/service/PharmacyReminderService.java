package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.PharmacyReminder;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.Tenant;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.PharmacyReminderRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Pharmacy Spec v2 §12D — customer SMS reminders. The pharmacist
 * defines reminders with template messages; an hourly scheduler
 * picks up due rows, interpolates {@code {firstName} / {productName}
 * / {storeName} / {websiteUrl}}, dispatches via Brevo
 * ({@link SmsSender}), and — for recurring reminders — inserts the
 * next occurrence.
 *
 * <p>Recurring reminders are modeled as a chain of single-shot rows
 * rather than a self-resetting row: each send produces a SENT (or
 * FAILED) audit row plus, conditionally, a fresh SCHEDULED row at
 * the next occurrence. Lets the dashboard show "12 reminders sent"
 * over a window without separate tally tables.
 */
@Service
public class PharmacyReminderService {

    private static final Logger log = LoggerFactory.getLogger(PharmacyReminderService.class);

    private final PharmacyReminderRepository repository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final TenantRepository tenantRepository;
    private final SmsSender smsSender;
    private final TenantSession tenantSession;
    private final Clock clock;
    private final String appBaseUrl;

    public PharmacyReminderService(PharmacyReminderRepository repository,
                                   CustomerRepository customerRepository,
                                   ProductRepository productRepository,
                                   TenantRepository tenantRepository,
                                   SmsSender smsSender,
                                   TenantSession tenantSession,
                                   Clock clock,
                                   @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.tenantRepository = tenantRepository;
        this.smsSender = smsSender;
        this.tenantSession = tenantSession;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public PharmacyReminder create(UUID customerId, UUID productId, String reminderType,
                                   String message, OffsetDateTime scheduledAt,
                                   String recurrence, OffsetDateTime recurrenceEnd,
                                   UUID createdBy) {
        tenantSession.bind();
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt is required");
        }
        String validatedRecurrence = normaliseRecurrence(recurrence);
        return repository.save(new PharmacyReminder(TenantContext.require(), customerId,
                productId, reminderType, message, scheduledAt,
                validatedRecurrence, recurrenceEnd, createdBy));
    }

    @Transactional(readOnly = true)
    public Page<PharmacyReminder> list(UUID customerId, String reminderType, String status,
                                       Pageable pageable) {
        tenantSession.bind();
        Specification<PharmacyReminder> spec = (root, query, cb) -> {
            jakarta.persistence.criteria.Predicate predicate = cb.conjunction();
            if (customerId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("customerId"), customerId));
            }
            if (reminderType != null && !reminderType.isBlank()) {
                predicate = cb.and(predicate, cb.equal(root.get("reminderType"), reminderType));
            }
            if (status != null && !status.isBlank()) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            return predicate;
        };
        return repository.findAll(spec, pageable);
    }

    @Transactional
    public PharmacyReminder cancel(UUID id) {
        tenantSession.bind();
        PharmacyReminder reminder = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Reminder not found"));
        if (!PharmacyReminder.STATUS_SCHEDULED.equals(reminder.getStatus())) {
            throw new IllegalArgumentException("Only SCHEDULED reminders can be cancelled");
        }
        reminder.markCancelled();
        return repository.save(reminder);
    }

    /**
     * Cross-tenant due-queue read used by the hourly scheduler.
     * Caller (the cron) opens its own transaction; we widen RLS with
     * {@code app.cross_tenant=true} for this read so every tenant's
     * SCHEDULED rows surface.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<PharmacyReminder> findDue() {
        tenantSession.bindCrossTenant();
        return repository.findDueAcrossTenants(OffsetDateTime.now(clock));
    }

    /**
     * Send one due reminder. Runs in a fresh transaction per row so
     * one customer's failure doesn't roll back another's success.
     * Side-effects:
     * <ul>
     *   <li>Interpolate {@code {firstName} / {productName} /
     *       {storeName} / {websiteUrl}} from customer + product +
     *       tenant context.</li>
     *   <li>Call {@link SmsSender#send} — failures mark the row
     *       FAILED with the message captured.</li>
     *   <li>If recurring, insert a new SCHEDULED row at the next
     *       occurrence.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessResult send(UUID reminderId) {
        tenantSession.bindCrossTenant();
        PharmacyReminder reminder = repository.findById(reminderId).orElse(null);
        if (reminder == null || !PharmacyReminder.STATUS_SCHEDULED.equals(reminder.getStatus())) {
            return new ProcessResult(reminderId, false, "Not in SCHEDULED state");
        }
        // Bind to the reminder's tenant for downstream RLS-scoped reads.
        TenantContext.set(reminder.getTenantId());
        tenantSession.bind();
        Customer customer = customerRepository.findById(reminder.getCustomerId()).orElse(null);
        if (customer == null) {
            reminder.markFailed(OffsetDateTime.now(clock), "customer not found");
            repository.save(reminder);
            return new ProcessResult(reminderId, false, "customer not found");
        }
        Product product = reminder.getProductId() == null ? null
                : productRepository.findById(reminder.getProductId()).orElse(null);
        Tenant tenant = tenantRepository.findById(reminder.getTenantId()).orElse(null);
        String message = interpolate(reminder.getMessage(), customer, product, tenant);
        String phone = customer.getPhone();
        if (phone == null || phone.isBlank()) {
            reminder.markFailed(OffsetDateTime.now(clock), "customer has no phone on file");
            repository.save(reminder);
            return new ProcessResult(reminderId, false, "no phone");
        }
        try {
            smsSender.send(phone, message);
            reminder.markSent(OffsetDateTime.now(clock));
            repository.save(reminder);
            scheduleNextOccurrence(reminder);
            return new ProcessResult(reminderId, true, null);
        } catch (RuntimeException ex) {
            log.warn("Reminder {} send failed: {}", reminderId, ex.getMessage());
            reminder.markFailed(OffsetDateTime.now(clock), ex.getMessage());
            repository.save(reminder);
            return new ProcessResult(reminderId, false, ex.getMessage());
        }
    }

    String interpolate(String template, Customer customer, Product product, Tenant tenant) {
        String firstName = customer.getFullName() == null
                ? "there" : customer.getFullName().split(" ")[0];
        String productName = product == null ? ""
                : (product.getNameGeneric() == null ? product.getName() : product.getNameGeneric());
        String storeName = tenant == null ? "your pharmacy" : tenant.getName();
        return template
                .replace("{firstName}", firstName)
                .replace("{productName}", productName)
                .replace("{storeName}", storeName)
                .replace("{websiteUrl}", appBaseUrl);
    }

    private void scheduleNextOccurrence(PharmacyReminder current) {
        String recurrence = current.getRecurrence();
        if (recurrence == null || PharmacyReminder.RECURRENCE_ONCE.equals(recurrence)) {
            return;
        }
        OffsetDateTime next = switch (recurrence) {
            case PharmacyReminder.RECURRENCE_DAILY -> current.getScheduledAt().plus(1, ChronoUnit.DAYS);
            case PharmacyReminder.RECURRENCE_WEEKLY -> current.getScheduledAt().plus(7, ChronoUnit.DAYS);
            case PharmacyReminder.RECURRENCE_MONTHLY -> current.getScheduledAt().plus(30, ChronoUnit.DAYS);
            default -> null;
        };
        if (next == null) {
            return;
        }
        if (current.getRecurrenceEnd() != null && next.isAfter(current.getRecurrenceEnd())) {
            return;
        }
        repository.save(new PharmacyReminder(current.getTenantId(), current.getCustomerId(),
                current.getProductId(), current.getReminderType(), current.getMessage(),
                next, recurrence, current.getRecurrenceEnd(), current.getCreatedBy()));
    }

    private static String normaliseRecurrence(String recurrence) {
        if (recurrence == null || recurrence.isBlank()) {
            return null;
        }
        String upper = recurrence.trim().toUpperCase();
        return switch (upper) {
            case "ONCE", "DAILY", "WEEKLY", "MONTHLY" -> upper;
            default -> throw new IllegalArgumentException(
                    "recurrence must be ONCE | DAILY | WEEKLY | MONTHLY");
        };
    }

    public record ProcessResult(UUID reminderId, boolean sent, String error) {
    }
}
