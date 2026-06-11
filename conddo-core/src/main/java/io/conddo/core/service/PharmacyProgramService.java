package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.BillingPaystackTransaction;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.PharmacyProgram;
import io.conddo.core.domain.PharmacyProgramEnrollment;
import io.conddo.core.domain.PharmacyProgramItem;
import io.conddo.core.domain.Product;
import io.conddo.core.paystack.PaystackGateway;
import io.conddo.core.repository.BillingPaystackTransactionRepository;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.PharmacyProgramEnrollmentRepository;
import io.conddo.core.repository.PharmacyProgramItemRepository;
import io.conddo.core.repository.PharmacyProgramRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pharmacy Roadmap Beta 3 — drug programs lifecycle. CRUD on the
 * program + items, publish toggle, enrollment lifecycle gated by
 * Paystack (HANDOFF_2026-06-11 §3 + §8). Recurring renewals are
 * captured as pharmacy_program_charges audit rows when the webhook
 * lands; the first-month charge runs through
 * {@link PaystackGateway#initialize} like every other Paystack flow.
 */
@Service
public class PharmacyProgramService {

    private static final char[] REF_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final PharmacyProgramRepository programRepository;
    private final PharmacyProgramItemRepository itemRepository;
    private final PharmacyProgramEnrollmentRepository enrollmentRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final BillingPaystackTransactionRepository transactionRepository;
    private final PaystackGateway paystackGateway;
    private final TenantSession tenantSession;
    private final Clock clock;
    private final String callbackUrl;
    private final SecureRandom rng = new SecureRandom();

    public PharmacyProgramService(PharmacyProgramRepository programRepository,
                                  PharmacyProgramItemRepository itemRepository,
                                  PharmacyProgramEnrollmentRepository enrollmentRepository,
                                  ProductRepository productRepository,
                                  CustomerRepository customerRepository,
                                  BillingPaystackTransactionRepository transactionRepository,
                                  PaystackGateway paystackGateway,
                                  TenantSession tenantSession,
                                  Clock clock,
                                  @Value("${conddo.paystack.callback-url:https://app.conddo.io/settings/billing/return}")
                                  String callbackUrl) {
        this.programRepository = programRepository;
        this.itemRepository = itemRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.paystackGateway = paystackGateway;
        this.tenantSession = tenantSession;
        this.clock = clock;
        this.callbackUrl = callbackUrl;
    }

    // ----- CRUD --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProgramView> list() {
        tenantSession.bind();
        List<PharmacyProgram> programs = programRepository.findAllByOrderByCreatedAtDesc();
        return programs.stream().map(p -> toView(p, itemRepository.findByProgramId(p.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<ProgramView> listPublished() {
        tenantSession.bind();
        return programRepository.findByPublishedTrueAndActiveTrueOrderByCreatedAtDesc()
                .stream().map(p -> toView(p, itemRepository.findByProgramId(p.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ProgramView get(UUID programId) {
        tenantSession.bind();
        PharmacyProgram program = programRepository.findById(programId)
                .orElseThrow(() -> new NotFoundException("Program not found"));
        return toView(program, itemRepository.findByProgramId(programId));
    }

    @Transactional
    public ProgramView create(ProgramInput input, UUID createdBy) {
        tenantSession.bind();
        if (input.name == null || input.name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (input.monthlyPrice == null || input.monthlyPrice.signum() <= 0) {
            throw new IllegalArgumentException("monthlyPrice must be > 0");
        }
        PharmacyProgram program = new PharmacyProgram(TenantContext.require(),
                input.name, input.monthlyPrice, createdBy);
        applyOptional(program, input);
        program = programRepository.save(program);
        replaceItems(program.getId(), input.items);
        return toView(program, itemRepository.findByProgramId(program.getId()));
    }

    @Transactional
    public ProgramView update(UUID programId, ProgramInput input) {
        tenantSession.bind();
        PharmacyProgram program = programRepository.findById(programId)
                .orElseThrow(() -> new NotFoundException("Program not found"));
        if (input.name != null) {
            program.setName(input.name);
        }
        if (input.monthlyPrice != null) {
            program.setMonthlyPrice(input.monthlyPrice);
        }
        applyOptional(program, input);
        program = programRepository.save(program);
        if (input.items != null) {
            replaceItems(program.getId(), input.items);
        }
        return toView(program, itemRepository.findByProgramId(program.getId()));
    }

    @Transactional
    public ProgramView setPublished(UUID programId, boolean published) {
        tenantSession.bind();
        PharmacyProgram program = programRepository.findById(programId)
                .orElseThrow(() -> new NotFoundException("Program not found"));
        program.setPublished(published);
        program = programRepository.save(program);
        return toView(program, itemRepository.findByProgramId(programId));
    }

    @Transactional(readOnly = true)
    public List<PharmacyProgramEnrollment> listEnrollments(UUID programId) {
        tenantSession.bind();
        return enrollmentRepository.findByProgramIdOrderByEnrolledAtDesc(programId);
    }

    // ----- enrol -------------------------------------------------------------

    /**
     * Manual or self-enrol on a program. Creates a
     * PENDING_PAYMENT enrollment row, initialises a Paystack
     * transaction for the first month's charge, and returns the
     * hosted URL + reference for the FE to redirect to.
     */
    @Transactional
    public EnrollResult enroll(UUID programId, UUID customerId, UUID enrolledBy) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        PharmacyProgram program = programRepository.findById(programId)
                .orElseThrow(() -> new NotFoundException("Program not found"));
        if (!program.isActive()) {
            throw new IllegalArgumentException("Program is inactive");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        String email = customer.getEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "Customer has no email on file; required for Paystack enrolment");
        }
        OffsetDateTime endsAt = program.getDurationMonths() == null ? null
                : OffsetDateTime.now(clock).plus(program.getDurationMonths(), ChronoUnit.MONTHS);
        PharmacyProgramEnrollment enrollment = enrollmentRepository.save(
                new PharmacyProgramEnrollment(tenantId, programId, customerId, endsAt, enrolledBy));

        long amountKobo = program.getMonthlyPrice()
                .multiply(BigDecimal.valueOf(100)).longValueExact();
        String reference = generateReference();
        BillingPaystackTransaction tx = new BillingPaystackTransaction(
                tenantId, reference, null, "monthly", amountKobo, enrolledBy);
        tx.setPurpose(BillingPaystackTransaction.PURPOSE_PROGRAM_ENROLLMENT);
        tx.setEnrollmentId(enrollment.getId());
        transactionRepository.save(tx);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", tenantId.toString());
        metadata.put("programId", program.getId().toString());
        metadata.put("programName", program.getName());
        metadata.put("enrollmentId", enrollment.getId().toString());
        metadata.put("purpose", "PROGRAM_ENROLLMENT");

        PaystackGateway.InitResult init = paystackGateway.initialize(email, amountKobo, reference,
                callbackUrl, metadata);
        return new EnrollResult(enrollment, init.authorizationUrl(),
                init.reference(), init.accessCode());
    }

    /** Called by the webhook reconciler on charge.success of a PROGRAM_ENROLLMENT row. */
    @Transactional
    public void activateEnrollment(UUID enrollmentId, String paystackSubscriptionCode) {
        tenantSession.bindCrossTenant();
        PharmacyProgramEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new NotFoundException("Enrollment not found"));
        if (PharmacyProgramEnrollment.STATUS_ACTIVE.equals(enrollment.getStatus())) {
            return;   // idempotent
        }
        enrollment.activate(paystackSubscriptionCode,
                OffsetDateTime.now(clock).plus(1, ChronoUnit.MONTHS));
        enrollmentRepository.save(enrollment);
    }

    // ----- helpers + shapes --------------------------------------------------

    private static void applyOptional(PharmacyProgram program, ProgramInput input) {
        if (input.description != null) {
            program.setDescription(input.description);
        }
        if (input.targetCondition != null) {
            program.setTargetCondition(input.targetCondition);
        }
        if (input.durationMonths != null) {
            program.setDurationMonths(input.durationMonths);
        }
        if (input.isActive != null) {
            program.setActive(input.isActive);
        }
    }

    private void replaceItems(UUID programId, List<ItemInput> items) {
        if (items == null) {
            return;
        }
        itemRepository.deleteByProgramId(programId);
        UUID tenantId = TenantContext.require();
        for (ItemInput it : items) {
            String freq = it.frequency == null ? "MONTHLY" : it.frequency.trim().toUpperCase();
            itemRepository.save(new PharmacyProgramItem(tenantId, programId,
                    it.productId, it.quantity, freq));
        }
    }

    private ProgramView toView(PharmacyProgram program, List<PharmacyProgramItem> items) {
        long activeCount = enrollmentRepository.countByProgramIdAndStatus(
                program.getId(), PharmacyProgramEnrollment.STATUS_ACTIVE);
        List<ItemView> itemViews = new ArrayList<>();
        for (PharmacyProgramItem item : items) {
            Product p = productRepository.findById(item.getProductId()).orElse(null);
            itemViews.add(new ItemView(item.getId(), item.getProductId(),
                    p == null ? null : (p.getNameGeneric() == null ? p.getName() : p.getNameGeneric()),
                    item.getQuantity(), item.getFrequency()));
        }
        return new ProgramView(program, itemViews, activeCount);
    }

    private String generateReference() {
        StringBuilder sb = new StringBuilder("CONDDO_PROG_");
        for (int i = 0; i < 10; i++) {
            sb.append(REF_ALPHABET[rng.nextInt(REF_ALPHABET.length)]);
        }
        return sb.toString();
    }

    // ----- DTOs --------------------------------------------------------------

    public record ProgramInput(String name, String description, String targetCondition,
                               Integer durationMonths, BigDecimal monthlyPrice,
                               Boolean isActive, List<ItemInput> items) {
    }

    public record ItemInput(UUID productId, int quantity, String frequency) {
    }

    public record ProgramView(PharmacyProgram program, List<ItemView> items, long enrollmentsCount) {
    }

    public record ItemView(UUID id, UUID productId, String productName,
                           int quantity, String frequency) {
    }

    public record EnrollResult(PharmacyProgramEnrollment enrollment,
                               String authorizationUrl, String reference, String accessCode) {
    }
}
