package io.conddo.payments.web.dto;

import io.conddo.payments.domain.Payment;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape for payment endpoints. {@code amountKobo} stays as the integer
 * minor unit; the FE formats Naira display. {@code paymentUrl} is RoutePay's
 * hosted checkout link — the FE redirects the customer there on init.
 */
public record PaymentResponse(UUID id,
                              String reference,
                              UUID tenantId,
                              UUID orderId,
                              UUID bookingId,
                              UUID customerId,
                              String customerEmail,
                              String customerName,
                              String description,
                              String status,
                              long amountKobo,
                              String currency,
                              Long feeKobo,
                              String paymentUrl,
                              OffsetDateTime paidAt,
                              String failureReason,
                              OffsetDateTime createdAt,
                              OffsetDateTime updatedAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getRoutepayReference(), p.getTenantId(),
                p.getOrderId(), p.getBookingId(), p.getCustomerId(),
                p.getCustomerEmail(), p.getCustomerName(), p.getDescription(),
                p.getStatus().name(), p.getAmountKobo(), p.getCurrency(),
                p.getFeeKobo(), p.getPaymentUrl(), p.getPaidAt(), p.getFailureReason(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
