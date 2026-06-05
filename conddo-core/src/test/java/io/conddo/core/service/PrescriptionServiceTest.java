package io.conddo.core.service;

import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Prescription;
import io.conddo.core.domain.Tenant;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.PrescriptionRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins PHARMACY_DEEP_DIVE_SPEC §1-5 contracts:
 *   - next_refill_due derivation across all three input shapes
 *   - fill stamps last_filled_at and recomputes due
 *   - remind goes through SmsSender with the right template
 *   - 422 when customer has no phone
 *   - 12-hour reminder de-dupe is a silent no-op
 */
class PrescriptionServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-05T10:00:00Z");

    private final PrescriptionRepository prescriptionRepository = mock(PrescriptionRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final TenantSession tenantSession = mock(TenantSession.class);
    private final SmsSender smsSender = mock(SmsSender.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final PrescriptionService service = new PrescriptionService(
            prescriptionRepository, customerRepository, tenantRepository, tenantSession, smsSender, clock);

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT_ID);
        Tenant tenant = new Tenant("Seb&Bayor Pharmaceuticals", "seb-bayor", "pharmacy", "starter");
        setField(Tenant.class, tenant, "id", TENANT_ID);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        Customer customer = customer(CUSTOMER_ID, "Chinedu Okafor", "+2348012345678");
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0);
            if (p.getId() == null) {
                setField(Prescription.class, p, "id", UUID.randomUUID());
            }
            return p;
        });
    }

    // ----- derivation --------------------------------------------------------

    @Test
    void oneOffHasNoNextRefillDue() {
        PrescriptionService.PrescriptionView v = service.create(CUSTOMER_ID, null, null,
                "Augmentin 625mg", "1 every 8h x 7d", 21, null, "Sinus infection");
        assertNull(v.prescription().getNextRefillDue(),
                "refillIntervalDays=null → one-off → next_refill_due null");
    }

    @Test
    void recurringNeverFilledDerivedFromIssuedAt() {
        // Brand new prescription issued at NOW, 30-day interval → due NOW + 30d.
        PrescriptionService.PrescriptionView v = service.create(CUSTOMER_ID, null, null,
                "Lisinopril 10mg", "1 daily", 30, 30, null);
        assertEquals(LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusDays(30),
                v.prescription().getNextRefillDue());
    }

    @Test
    void fillRecomputesDueFromLastFilledAt() {
        // Create then immediately fill — next due jumps from "issued+30" to "today+30".
        PrescriptionService.PrescriptionView v = service.create(CUSTOMER_ID, null, null,
                "Lisinopril 10mg", "1 daily", 30, 30, null);
        UUID id = v.prescription().getId();
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(v.prescription()));
        // Move the clock forward 5 days so the recomputed date is observably different.
        Clock later = Clock.fixed(NOW.plusSeconds(5L * 24 * 3600), ZoneOffset.UTC);
        PrescriptionService later_service = new PrescriptionService(prescriptionRepository,
                customerRepository, tenantRepository, tenantSession, smsSender, later);

        PrescriptionService.PrescriptionView filled = later_service.fill(id);

        assertNotNull(filled.prescription().getLastFilledAt());
        // Due date = last_filled_at::date + 30
        LocalDate expected = LocalDate.ofInstant(NOW.plusSeconds(5L * 24 * 3600), ZoneOffset.UTC).plusDays(30);
        assertEquals(expected, filled.prescription().getNextRefillDue());
    }

    // ----- remind ------------------------------------------------------------

    @Test
    void remindGoesThroughSmsSenderWithDefaultTemplate() {
        PrescriptionService.PrescriptionView v = service.create(CUSTOMER_ID, null, null,
                "Amlodipine 5mg", "1 daily", 30, 30, null);
        UUID id = v.prescription().getId();
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(v.prescription()));

        service.remind(id, null);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(eq("+2348012345678"), text.capture());
        assertEquals(true, text.getValue().contains("Chinedu"));
        assertEquals(true, text.getValue().contains("Amlodipine"));
        assertEquals(true, text.getValue().contains("Seb&Bayor"));
    }

    @Test
    void remindUsesCustomMessageVerbatimWhenProvided() {
        PrescriptionService.PrescriptionView v = service.create(CUSTOMER_ID, null, null,
                "Amlodipine 5mg", "1 daily", 30, 30, null);
        UUID id = v.prescription().getId();
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(v.prescription()));

        service.remind(id, "Please come by today — your medication is ready.");

        verify(smsSender).send("+2348012345678",
                "Please come by today — your medication is ready.");
    }

    @Test
    void remindThrowsWhenCustomerHasNoPhone() {
        UUID phonelessCustomer = UUID.randomUUID();
        when(customerRepository.findById(phonelessCustomer))
                .thenReturn(Optional.of(customer(phonelessCustomer, "Tunde Adigun", null)));
        PrescriptionService.PrescriptionView v = service.create(phonelessCustomer, null, null,
                "Vitamin C", "1 tablet", 30, 30, null);
        UUID id = v.prescription().getId();
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(v.prescription()));

        assertThrows(PrescriptionService.NoCustomerPhoneException.class,
                () -> service.remind(id, null));
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void remindSilentlyDeduplicatesWithin12Hours() {
        PrescriptionService.PrescriptionView v = service.create(CUSTOMER_ID, null, null,
                "Amlodipine 5mg", "1 daily", 30, 30, null);
        v.prescription().recordReminder(OffsetDateTime.now(clock).minusHours(2));
        UUID id = v.prescription().getId();
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(v.prescription()));

        service.remind(id, null);   // should silently skip
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void remindFiresAfterDeduplicationWindowPasses() {
        PrescriptionService.PrescriptionView v = service.create(CUSTOMER_ID, null, null,
                "Amlodipine 5mg", "1 daily", 30, 30, null);
        v.prescription().recordReminder(OffsetDateTime.now(clock).minusHours(13));
        UUID id = v.prescription().getId();
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(v.prescription()));

        service.remind(id, null);
        verify(smsSender).send(eq("+2348012345678"), anyString());
    }

    // ----- create branches ---------------------------------------------------

    @Test
    void createRequiresMedication() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(CUSTOMER_ID, null, null, "", "1 daily", 30, 30, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(CUSTOMER_ID, null, null, null, "1 daily", 30, 30, null));
    }

    @Test
    void createRequiresEitherCustomerIdOrCustomerName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(null, null, "+2348012345678",
                        "Lisinopril 10mg", "1 daily", 30, 30, null));
    }

    @Test
    void createWithNameCreatesCustomerOnTheFly() {
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            setField(Customer.class, c, "id", UUID.randomUUID());
            // Make subsequent findById find it (view loop in service).
            when(customerRepository.findById(c.getId())).thenReturn(Optional.of(c));
            return c;
        });

        PrescriptionService.PrescriptionView v = service.create(null, "Walk-in Patient", "+2349000000000",
                "Paracetamol 500mg", "1 every 8h", 6, null, null);

        verify(customerRepository).save(any(Customer.class));
        assertNotNull(v.prescription().getCustomerId());
    }

    // ----- helpers -----------------------------------------------------------

    private static Customer customer(UUID id, String name, String phone) {
        Customer c = new Customer(TENANT_ID, name, null, phone, null);
        setField(Customer.class, c, "id", id);
        return c;
    }

    private static void setField(Class<?> type, Object target, String name, Object value) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
