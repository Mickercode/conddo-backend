package io.conddo.api.signup;

import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.CustomerService;
import io.conddo.core.service.InventoryService;
import io.conddo.core.service.OrderService;
import io.conddo.core.signup.TenantActivatedEvent;
import io.conddo.core.vertical.VerticalConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the seeder's per-vertical dispatch + the kill-switch: fashion gets
 * fashion-flavoured records, pharmacy gets pharmacy-flavoured records, an
 * unsupported vertical stays silent, and the property gate suppresses
 * everything.
 */
class VerticalSignupSeederTest {

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final CustomerService customerService = mock(CustomerService.class);
    private final InventoryService inventoryService = mock(InventoryService.class);
    private final OrderService orderService = mock(OrderService.class);

    private final io.conddo.core.service.PrescriptionService prescriptionService =
            mock(io.conddo.core.service.PrescriptionService.class);

    private VerticalSignupSeeder seeder(boolean enabled) {
        when(customerService.create(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> customer(inv.getArgument(0)));
        return new VerticalSignupSeeder(tenantRepository, customerService,
                inventoryService, orderService, prescriptionService, enabled);
    }

    @Test
    void fashionSeedsThreeCustomersTwoOrdersAndNoInventory() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant("fashion")));
        seeder(true).onTenantActivated_seedSampleData(new TenantActivatedEvent(tenantId));

        verify(customerService, times(3)).create(anyString(), anyString(), anyString(), anyString());
        verify(orderService, times(2)).create(any(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyString());
        verify(inventoryService, never()).create(anyString(), anyString(), any(),
                any(), anyInt(), anyInt(), any());
    }

    @Test
    void pharmacySeedsThreeCustomersFiveProductsOneOrderAndThreePrescriptions() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant("pharmacy")));
        seeder(true).onTenantActivated_seedSampleData(new TenantActivatedEvent(tenantId));

        verify(customerService, times(3)).create(anyString(), anyString(), anyString(), anyString());
        // Inventory now uses the 9-arg create with expiryDate + batchNumber.
        verify(inventoryService, times(5)).create(anyString(), anyString(), any(),
                any(BigDecimal.class), anyInt(), anyInt(), eq(true),
                org.mockito.ArgumentMatchers.<java.time.LocalDate>any(),
                org.mockito.ArgumentMatchers.<String>any());
        verify(orderService, times(1)).create(any(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyString());
        verify(prescriptionService, times(3)).create(any(UUID.class), org.mockito.ArgumentMatchers.<String>any(),
                org.mockito.ArgumentMatchers.<String>any(), anyString(), anyString(), anyInt(),
                org.mockito.ArgumentMatchers.<Integer>any(), anyString());
    }

    @Test
    void unsupportedVerticalSeedsNothing() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant("logistics")));
        seeder(true).onTenantActivated_seedSampleData(new TenantActivatedEvent(tenantId));

        verify(customerService, never()).create(any(), any(), any(), any());
        verify(inventoryService, never()).create(any(), any(), any(), any(), anyInt(), anyInt(), any());
        verify(orderService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void disabledFlagSuppressesEverything() {
        UUID tenantId = UUID.randomUUID();
        // Repository isn't even consulted when the flag is off.
        seeder(false).onTenantActivated_seedSampleData(new TenantActivatedEvent(tenantId));

        verify(tenantRepository, never()).findById(any());
        verify(customerService, never()).create(any(), any(), any(), any());
    }

    @Test
    void musicStudioSeedsThreeClientsThreeRoomsAndTwoSessions() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant("music-studio")));
        seeder(true).onTenantActivated_seedSampleData(new TenantActivatedEvent(tenantId));

        verify(customerService, times(3)).create(anyString(), anyString(), anyString(), anyString());
        // Three "rooms" — Studio A, Studio B, Podcast booth — each as an inventory item.
        verify(inventoryService, times(3)).create(anyString(), anyString(), any(),
                any(BigDecimal.class), anyInt(), anyInt(), eq(true));
        // Two sessions in different stages (Deposit Paid + In Session).
        verify(orderService, times(2)).create(any(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyString());
    }

    @Test
    void musicStudioUnderscoreVerticalAlsoSeeds() {
        // The vertical id stored on tenants is `music-studio` (kebab-case), but the
        // legacy `music_studio` (snake-case) form lives in some configs. Both fire.
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant("music_studio")));
        seeder(true).onTenantActivated_seedSampleData(new TenantActivatedEvent(tenantId));
        verify(customerService, times(3)).create(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void verticalIsCaseInsensitive() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant("PHARMACY")));
        seeder(true).onTenantActivated_seedSampleData(new TenantActivatedEvent(tenantId));
        verify(customerService, atLeast(1)).create(anyString(), anyString(), anyString(), anyString());
    }

    // ----- helpers -----------------------------------------------------------

    private static Tenant tenant(String verticalId) {
        Tenant t = new Tenant("Sample Co", "sample-co", verticalId, "starter");
        setField(Tenant.class, t, "id", UUID.randomUUID());
        return t;
    }

    private static Customer customer(String fullName) {
        Customer c = new Customer(UUID.randomUUID(), fullName, "x@x.test", "+2348012345600", "note");
        setField(Customer.class, c, "id", UUID.randomUUID());
        return c;
    }

    @SuppressWarnings("unused")
    private static VerticalConfig anyConfig() {
        return new VerticalConfig("fashion", "Fashion", java.util.List.of(),
                java.util.List.of(), java.util.List.of());
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
