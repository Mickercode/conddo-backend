package io.conddo.api.web.dto;

import io.conddo.core.common.Initials;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Order;
import io.conddo.core.service.OrderService.Billing;
import io.conddo.core.service.OrderService.Detail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full order detail (§11.4): header, the pipeline (for the stepper), billing,
 * items, payments, measurements, the activity log, and the linked customer.
 */
public record OrderDetailResponse(
        UUID id,
        String reference,
        String service,
        String stage,
        List<String> stages,
        String flag,
        LocalDate dueDate,
        OffsetDateTime orderedAt,
        BigDecimal amount,
        BillingDto billing,
        CustomerRef customer,
        List<OrderItemDto> items,
        List<OrderPaymentDto> payments,
        Map<String, Object> measurements,
        String notes,
        List<OrderActivityDto> activity
) {

    /** Billing summary: total, paid-so-far ("deposit"), and outstanding balance. */
    public record BillingDto(BigDecimal total, BigDecimal deposit, BigDecimal balance) {
        static BillingDto from(Billing b) {
            return new BillingDto(b.total(), b.deposit(), b.balance());
        }
    }

    /** The linked customer's contact card (null when the order has no customer). */
    public record CustomerRef(UUID id, String name, String initials, String phone, String email) {
        static CustomerRef from(Customer c) {
            return new CustomerRef(c.getId(), c.getFullName(), Initials.of(c.getFullName()),
                    c.getPhone(), c.getEmail());
        }
    }

    public static OrderDetailResponse from(Detail d) {
        Order o = d.order();
        CustomerRef customer = d.customer() == null ? null : CustomerRef.from(d.customer());
        return new OrderDetailResponse(
                o.getId(), o.getReference(), o.getService(), o.getStage(), d.stages(), d.flag(),
                o.getDueDate(), o.getCreatedAt(), o.getAmount(), BillingDto.from(d.billing()), customer,
                d.items().stream().map(OrderItemDto::from).toList(),
                d.payments().stream().map(OrderPaymentDto::from).toList(),
                o.getMeasurements(), o.getNotes(),
                d.activity().stream().map(OrderActivityDto::from).toList());
    }
}
