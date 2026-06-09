package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.domain.Order;
import io.conddo.core.domain.OrderItem;
import io.conddo.core.service.PublicOrderCheckoutService;
import io.conddo.core.service.PublicOrderCheckoutService.CheckoutResult;
import io.conddo.core.service.PublicOrderCheckoutService.OrderWithItems;
import io.conddo.core.service.PublicOrderCheckoutService.RequestedItem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer-side order intake + history (PHARMACY_PUBLIC_API_SPEC §5).
 * Replaces the Phase-1 anonymous-buyer order endpoint that lived on
 * PublicSiteController. Customer JWT required; cart is on the customer's
 * id, address is one of their saved rows, prescription is one of their
 * submissions.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/pharmacy/orders")
public class PublicCustomerOrderController {

    private final PublicOrderCheckoutService checkoutService;
    private final CustomerJwtService customerJwtService;

    public PublicCustomerOrderController(PublicOrderCheckoutService checkoutService,
                                         CustomerJwtService customerJwtService) {
        this.checkoutService = checkoutService;
        this.customerJwtService = customerJwtService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> place(@Valid @RequestBody PlaceOrderRequest body,
                                                     HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        List<RequestedItem> items = body.items().stream()
                .map(i -> new RequestedItem(i.productId(), i.quantity()))
                .toList();
        CheckoutResult result = checkoutService.checkout(customerId, items,
                body.addressId(), body.prescriptionId(), body.notes(),
                body.refillOfferCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "order", toCreatedShape(result)));
    }

    @GetMapping
    public Map<String, Object> mine(HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        return Map.of("orders", checkoutService.listMine(customerId).stream()
                .map(PublicCustomerOrderController::toListRow)
                .toList());
    }

    @GetMapping("/{orderId}")
    public Map<String, Object> detail(@PathVariable UUID orderId, HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        OrderWithItems detail = checkoutService.getMineWithItems(customerId, orderId);
        return Map.of("order", toDetailShape(detail.order(), detail.items()));
    }

    // ----- DTO shapes --------------------------------------------------------

    public record PlaceOrderRequest(
            @NotEmpty List<Item> items,
            @NotNull UUID addressId,
            String notes,
            UUID prescriptionId,
            String refillOfferCode) {

        public record Item(@NotNull UUID productId, @Positive int quantity) {
        }
    }

    private static Map<String, Object> toCreatedShape(CheckoutResult r) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.order().getId());
        m.put("status", r.order().getStage());
        m.put("subtotal", r.subtotal());
        m.put("deliveryFee", r.deliveryFee());
        m.put("total", r.total());
        m.put("paymentStatus", r.order().getPaymentStatus());
        m.put("paymentLink", r.order().getPaymentLink());
        m.put("createdAt", r.order().getCreatedAt());
        m.put("reference", r.order().getReference());
        return m;
    }

    private static Map<String, Object> toListRow(Order order) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", order.getId());
        m.put("reference", order.getReference());
        m.put("status", order.getStage());
        m.put("paymentStatus", order.getPaymentStatus());
        m.put("total", order.getAmount());
        m.put("createdAt", order.getCreatedAt());
        return m;
    }

    private Map<String, Object> toDetailShape(Order order, List<OrderItem> orderItems) {
        BigDecimal subtotal = order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
        BigDecimal deliveryFee = BigDecimal.valueOf(order.getDeliveryFeeKobo()).divide(BigDecimal.valueOf(100));
        subtotal = subtotal.subtract(deliveryFee);
        List<Map<String, Object>> items = new ArrayList<>();
        orderItems.forEach(it -> {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("description", it.getDescription());
            row.put("quantity", it.getQuantity());
            row.put("unitPrice", it.getUnitPrice());
            BigDecimal unit = it.getUnitPrice() == null ? BigDecimal.ZERO : it.getUnitPrice();
            row.put("lineTotal", unit.multiply(BigDecimal.valueOf(it.getQuantity())));
            items.add(row);
        });
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", order.getId());
        m.put("reference", order.getReference());
        m.put("status", order.getStage());
        m.put("paymentStatus", order.getPaymentStatus());
        m.put("subtotal", subtotal);
        m.put("deliveryFee", deliveryFee);
        m.put("total", order.getAmount());
        m.put("notes", order.getNotes());
        m.put("paymentLink", order.getPaymentLink());
        m.put("items", items);
        m.put("address", order.getAddressSnapshot());
        m.put("prescriptionId", order.getPrescriptionId());
        m.put("createdAt", order.getCreatedAt());
        m.put("updatedAt", order.getUpdatedAt());
        return m;
    }
}
