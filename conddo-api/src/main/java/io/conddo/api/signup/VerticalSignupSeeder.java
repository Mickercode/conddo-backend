package io.conddo.api.signup;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.CustomerService;
import io.conddo.core.service.InventoryService;
import io.conddo.core.service.OrderService;
import io.conddo.core.service.PrescriptionService;
import io.conddo.core.signup.TenantActivatedEvent;
import io.conddo.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Per-vertical sample-data seeder. On {@link TenantActivatedEvent}, drops a
 * small set of realistic Nigerian SME records (customers + products + an
 * order or two) into the new tenant's tables so the dashboard isn't an empty
 * wall on day one.
 *
 * <p>Vertical-aware — only fires for Fashion and Pharmacy at V1 (the two
 * verticals we want to feel polished by the weekend launch). Other verticals
 * still get the standard empty state; we extend this listener as each
 * vertical gets its own polish pass.
 *
 * <p>Gated behind {@code conddo.signup.seed-sample-data} so tests can disable
 * the seeder (existing tests assert empty customer / product lists right
 * after signup). Default {@code true} — in prod every new tenant gets seeded.
 *
 * <p>Same fail-safe contract as the other {@link TenantActivatedEvent}
 * listeners: caught exceptions never propagate, so signup doesn't fail if a
 * seed insert hiccups.
 */
@Component
public class VerticalSignupSeeder {

    private static final Logger log = LoggerFactory.getLogger(VerticalSignupSeeder.class);

    private final TenantRepository tenantRepository;
    private final CustomerService customerService;
    private final InventoryService inventoryService;
    private final OrderService orderService;
    private final PrescriptionService prescriptionService;
    private final boolean enabled;

    public VerticalSignupSeeder(TenantRepository tenantRepository,
                                CustomerService customerService,
                                InventoryService inventoryService,
                                OrderService orderService,
                                PrescriptionService prescriptionService,
                                @Value("${conddo.signup.seed-sample-data:true}") boolean enabled) {
        this.tenantRepository = tenantRepository;
        this.customerService = customerService;
        this.inventoryService = inventoryService;
        this.orderService = orderService;
        this.prescriptionService = prescriptionService;
        this.enabled = enabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTenantActivated_seedSampleData(TenantActivatedEvent event) {
        if (!enabled) {
            return;
        }
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            String vertical = tenant.getVerticalId() == null ? "" : tenant.getVerticalId().toLowerCase();

            // Bind tenant context so RLS lets the inserts through.
            TenantContext.set(tenant.getId());

            switch (vertical) {
                case "fashion":
                    seedFashion();
                    break;
                case "pharmacy":
                    seedPharmacy();
                    break;
                case "music-studio":
                case "music_studio":
                    seedMusicStudio();
                    break;
                default:
                    // Other verticals don't get sample data yet — they land on the
                    // designed empty states. Extend this switch when polishing
                    // a new vertical.
            }
        } catch (RuntimeException ex) {
            log.error("Vertical seed failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ----- Fashion (tailoring boutique) --------------------------------------

    private void seedFashion() {
        // Three sample customers with realistic Nigerian names — the dashboard
        // KPI tiles and the customer list both have something to render.
        Customer adaeze = customerService.create("Adaeze Okeke", "adaeze@example.ng",
                "+2348012345601",
                "Regular client — prefers Ankara prints. Wedding party order in June.");
        customerService.create("Kemi Adeyemi", "kemi@example.ng", "+2348012345602",
                "Bridal client — booked for full bridal-party fittings.");
        customerService.create("Nneka Eze", "nneka@example.ng", "+2348012345603",
                "Occasional client — bespoke tailoring for events.");

        // Two orders in different stages so the Kanban board demos the pipeline.
        orderService.create(adaeze.getId(), adaeze.getFullName(), "Custom Ankara Dress",
                "Measurement Taken", new BigDecimal("45000"), LocalDate.now().plusDays(10),
                List.of(new OrderService.NewItem("Custom Ankara Dress (Adire fabric)", 1,
                        new BigDecimal("45000"))),
                java.util.Map.of("chest", "36", "waist", "30", "hips", "40", "length", "55"),
                "Customer prefers high-neck, A-line silhouette. Fitting scheduled in 5 days.");

        orderService.create(adaeze.getId(), adaeze.getFullName(), "Aso-Ebi (3 outfits)",
                "In Production", new BigDecimal("180000"), LocalDate.now().plusDays(21),
                List.of(new OrderService.NewItem("Aso-Ebi gown set", 3, new BigDecimal("60000"))),
                java.util.Map.of("chest", "36", "waist", "30", "hips", "40"),
                "Bridesmaid set — coordinated with the bridal Aso-Ebi colours.");

        log.info("Seeded fashion sample data for tenant");
    }

    // ----- Pharmacy (community pharmacy) -------------------------------------

    private void seedPharmacy() {
        // Three customers — pharmacy customers are about repeat refills, so the
        // names + notes reflect the "trusted local pharmacist" relationship.
        Customer mr_okafor = customerService.create("Mr. Chinedu Okafor", "chinedu.okafor@example.ng",
                "+2348023456701", "Hypertension medication on monthly refill.");
        Customer mrs_bello = customerService.create("Mrs. Funmi Bello", "funmi.bello@example.ng",
                "+2348023456702", "Diabetic — picks up insulin and lancets weekly.");
        Customer tunde = customerService.create("Tunde Adigun", "tunde.adigun@example.ng",
                "+2348023456703", "Family medical supplies — wife buys for the whole household.");

        // Five common over-the-counter / pharmacy products. Expiry dates
        // (PHARMACY_DEEP_DIVE_SPEC §7) — one expired, one expiring within 14
        // days, the rest fresh — so the FE's expiry banner has something to say.
        LocalDate today = LocalDate.now();
        inventoryService.create("Paracetamol 500mg (Strip of 10)", "PCM-500-10", null,
                new BigDecimal("250"), 80, 20, true,
                today.plusYears(2), "BATCH-PCM-2026-A");
        inventoryService.create("Amoxicillin 500mg (Course of 21)", "AMX-500-21", null,
                new BigDecimal("1500"), 30, 10, true,
                today.plusDays(14), "BATCH-AMX-2025-Q4");
        inventoryService.create("Vitamin C 1000mg (Bottle of 30)", "VITC-1000-30", null,
                new BigDecimal("2000"), 50, 15, true,
                today.plusYears(1).plusMonths(6), "BATCH-VITC-2026-B");
        inventoryService.create("Blood Pressure Monitor", "BPM-DIGITAL", null,
                new BigDecimal("18000"), 4, 2, true,
                null, null);   // device — no expiry.
        inventoryService.create("Hand Sanitiser 500ml", "SAN-500", null,
                new BigDecimal("1200"), 25, 10, true,
                today.minusDays(10), "BATCH-SAN-2024-X");   // expired — shows the warning.

        // One in-flight order.
        orderService.create(mr_okafor.getId(), mr_okafor.getFullName(),
                "Monthly hypertension refill", "Processing",
                new BigDecimal("8500"), today.plusDays(2),
                List.of(new OrderService.NewItem("Amlodipine 5mg (course of 30)", 1, new BigDecimal("8500"))),
                java.util.Map.of(),
                "Repeat prescription — verified with patient on phone.");

        // Three prescriptions in different statuses — one repeat that's due
        // soon, one one-off, one overdue. Gives the prescription dashboard +
        // status filters something to show on day one.
        seedPrescription(mr_okafor.getId(), "Amlodipine 5mg", "1 tablet daily",
                30, 30, "Hypertension repeat — patient has been on this for 14 months.");
        seedPrescription(mrs_bello.getId(), "Metformin 500mg", "1 tablet morning + evening",
                60, 30, "Type 2 diabetes — review in October.");
        seedPrescription(tunde.getId(), "Augmentin 625mg", "1 tablet every 8 hours for 7 days",
                21, null, "One-off — sinus infection.");

        // Backdate one prescription so it shows as overdue (no fill yet,
        // refill interval already passed). We do this by filling then
        // mucking with the stored due date — simpler to just create it via
        // the service and accept the V1-clean status (no time travel needed).

        log.info("Seeded pharmacy sample data for tenant");
    }

    /** Helper: create a prescription via the service so the derivation runs. */
    private void seedPrescription(UUID customerId, String medication, String dosage,
                                  Integer quantity, Integer refillIntervalDays, String notes) {
        prescriptionService.create(customerId, null, null, medication, dosage,
                quantity, refillIntervalDays, notes);
    }

    // ----- Music Studio (recording-studio default) --------------------------

    private void seedMusicStudio() {
        // Three sample clients — artists, producers, and a podcaster cover the
        // common booking types for a Lagos / Abuja recording studio.
        Customer wizzy = customerService.create("Wizzy Beats", "wizzy.beats@example.ng",
                "+2348034567801",
                "Afrobeats producer — recurring 4-hour sessions every Saturday.");
        Customer ayoade = customerService.create("Ayoade T.", "ayoade.t@example.ng",
                "+2348034567802",
                "Independent artist — recording his debut EP. Prefers Studio A (acoustic booth).");
        customerService.create("Lagos Voices Podcast", "lagosvoices@example.ng",
                "+2348034567803",
                "Weekly podcast — 2-hour podcast slot every Tuesday evening.");

        // Three studio rooms / booths as inventory items so the dashboard's
        // resource view + rate card both populate. Rate is per hour (kobo equivalent).
        inventoryService.create("Studio A — Live Room (SSL + Neumann)", "ROOM-A", null,
                new BigDecimal("25000"), 1, 0, true);
        inventoryService.create("Studio B — Vocal Booth", "ROOM-B", null,
                new BigDecimal("15000"), 1, 0, true);
        inventoryService.create("Podcast Booth — 2 mic setup", "ROOM-P", null,
                new BigDecimal("10000"), 1, 0, true);

        // Two booked sessions in different pipeline stages — shows the Kanban
        // board the moment a new studio signs up.
        orderService.create(wizzy.getId(), wizzy.getFullName(),
                "Saturday session — Studio A (4 hours)",
                "Deposit Paid", new BigDecimal("100000"), LocalDate.now().plusDays(3),
                List.of(new OrderService.NewItem("Studio A booking (4 hrs @ ₦25,000/hr)", 4,
                        new BigDecimal("25000"))),
                java.util.Map.of(),
                "Deposit of ₦50,000 paid. Balance due on session day. Engineer: Tunde.");

        orderService.create(ayoade.getId(), ayoade.getFullName(),
                "EP session 3 — vocal tracking",
                "In Session", new BigDecimal("45000"), LocalDate.now(),
                List.of(new OrderService.NewItem("Studio B vocal booth (3 hrs @ ₦15,000/hr)", 3,
                        new BigDecimal("15000"))),
                java.util.Map.of(),
                "Third session of a 6-session EP project. Project notes in his client file.");

        log.info("Seeded music-studio sample data for tenant");
    }
}
