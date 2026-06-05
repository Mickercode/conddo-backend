package io.conddo.api.web;

import io.conddo.api.web.dto.BoardResponse;
import io.conddo.api.web.dto.CreateItemRequest;
import io.conddo.api.web.dto.CreateOrderRequest;
import io.conddo.api.web.dto.CreatePaymentRequest;
import io.conddo.api.web.dto.MeasurementsBody;
import io.conddo.api.web.dto.OrderActivityDto;
import io.conddo.api.web.dto.OrderCard;
import io.conddo.api.web.dto.OrderDetailResponse;
import io.conddo.api.web.dto.OrderItemDto;
import io.conddo.api.web.dto.OrderPaymentDto;
import io.conddo.api.web.dto.ReminderRequest;
import io.conddo.api.web.dto.TransitionRequest;
import io.conddo.api.web.dto.UpdateItemRequest;
import io.conddo.api.web.dto.UpdateOrderRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Order;
import io.conddo.core.service.OrderService;
import io.conddo.core.service.OrderService.OrderView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped order pipeline (§11.4): Kanban board, list, detail, transitions,
 * line items, payments, activity, reminders. Tenant comes from the JWT (RLS);
 * reads are open to any staff role, writes default to TENANT_ADMIN / SUPER_ADMIN.
 * Pipeline-stage management lives in {@link OrderStageController}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@io.conddo.api.billing.RequiresFeature(value = "order_management",
        requiredPlan = "Growth", requiredPlanPrice = 45000)
public class OrderController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/board")
    @PreAuthorize(READ)
    public ApiResponse<BoardResponse> board(@RequestParam(required = false) String search,
                                            @RequestParam(required = false) String filter) {
        return ApiResponse.ok(BoardResponse.from(orderService.board(search, filter)));
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<OrderCard>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<OrderView> result = orderService.list(search, filter, stage, PageRequest.of(page, size));
        List<OrderCard> rows = result.getContent().stream().map(OrderCard::from).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<OrderDetailResponse>> create(@Valid @RequestBody CreateOrderRequest request) {
        Order created = orderService.create(request.customerId(), request.customerName(), request.service(),
                request.stage(), request.amount(), request.dueDate(), request.toNewItems(),
                request.measurements(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(detail(created.getId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<OrderDetailResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(detail(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<OrderDetailResponse> update(@PathVariable UUID id,
                                                   @Valid @RequestBody UpdateOrderRequest request) {
        orderService.update(id, request.service(), request.dueDate(), request.flag(),
                request.amount(), request.notes(), request.amount() != null);
        return ApiResponse.ok(detail(id));
    }

    @PostMapping("/{id}/transition")
    @PreAuthorize(WRITE)
    public ApiResponse<OrderDetailResponse> transition(@PathVariable UUID id,
                                                       @Valid @RequestBody TransitionRequest request) {
        orderService.transition(id, request.stage());
        return ApiResponse.ok(detail(id));
    }

    @PutMapping("/{id}/measurements")
    @PreAuthorize(WRITE)
    public ApiResponse<MeasurementsBody> putMeasurements(@PathVariable UUID id, @RequestBody MeasurementsBody body) {
        return ApiResponse.ok(new MeasurementsBody(
                orderService.setMeasurements(id, body.measurements()).getMeasurements()));
    }

    // ----- items --------------------------------------------------------------

    @GetMapping("/{id}/items")
    @PreAuthorize(READ)
    public ApiResponse<List<OrderItemDto>> items(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.items(id).stream().map(OrderItemDto::from).toList());
    }

    @PostMapping("/{id}/items")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<OrderItemDto>> addItem(@PathVariable UUID id,
                                                             @Valid @RequestBody CreateItemRequest request) {
        OrderItemDto body = OrderItemDto.from(
                orderService.addItem(id, request.description(), request.quantity(), request.unitPrice()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PatchMapping("/{id}/items/{itemId}")
    @PreAuthorize(WRITE)
    public ApiResponse<OrderItemDto> updateItem(@PathVariable UUID id, @PathVariable UUID itemId,
                                                @RequestBody UpdateItemRequest request) {
        return ApiResponse.ok(OrderItemDto.from(
                orderService.updateItem(id, itemId, request.description(), request.quantity(), request.unitPrice())));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> deleteItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        orderService.deleteItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    // ----- payments -----------------------------------------------------------

    @GetMapping("/{id}/payments")
    @PreAuthorize(READ)
    public ApiResponse<List<OrderPaymentDto>> payments(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.payments(id).stream().map(OrderPaymentDto::from).toList());
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<OrderPaymentDto>> addPayment(@PathVariable UUID id,
                                                                   @Valid @RequestBody CreatePaymentRequest request) {
        OrderPaymentDto body = OrderPaymentDto.from(
                orderService.addPayment(id, request.amount(), request.method(), request.note()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    // ----- activity / reminders ----------------------------------------------

    @GetMapping("/{id}/activity")
    @PreAuthorize(READ)
    public ApiResponse<List<OrderActivityDto>> activity(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.activity(id).stream().map(OrderActivityDto::from).toList());
    }

    @PostMapping("/{id}/reminders")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> remind(@PathVariable UUID id, @RequestBody(required = false) ReminderRequest request) {
        orderService.remind(id, request == null ? null : request.message());
        return ResponseEntity.accepted().build();
    }

    private OrderDetailResponse detail(UUID id) {
        return OrderDetailResponse.from(orderService.detail(id));
    }
}
