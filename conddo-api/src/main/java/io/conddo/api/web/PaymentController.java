package io.conddo.api.web;

import io.conddo.api.web.dto.PaymentReminderRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.PaymentsService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Payments dashboard (§11.7) — KPI summary, the transactions ledger, and
 * outstanding-by-customer, all read-only aggregation over orders/payments
 * (RLS-scoped). Invoices, Paystack links, and the webhook live in the Billing
 * module (§7) and are not yet exposed here. Reads are open to any staff role;
 * sending a reminder is a {@code TENANT_ADMIN} action.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final PaymentsService paymentsService;

    public PaymentController(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    /** KPI cards: {@code {thisMonth, outstanding, paidInvoices, overdue}}. */
    @GetMapping("/summary")
    @PreAuthorize(READ)
    public ApiResponse<PaymentsService.Summary> summary(@RequestParam(required = false) String range) {
        return ApiResponse.ok(paymentsService.summary(range));
    }

    /** Transactions table, paginated. Rows: {@code {date, customer, description, amount, method, status}}. */
    @GetMapping("/transactions")
    @PreAuthorize(READ)
    public ApiResponse<List<PaymentsService.Txn>> transactions(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PaymentsService.Page result = paymentsService.transactions(filter, from, to, page, size);
        return ApiResponse.ok(result.content(),
                ApiResponse.Meta.page(result.page(), result.size(), result.total()));
    }

    /** Open balances grouped by customer, worst first: {@code {customerId, name, note, amount, tone}}. */
    @GetMapping("/outstanding")
    @PreAuthorize(READ)
    public ApiResponse<List<PaymentsService.OutstandingGroup>> outstanding() {
        return ApiResponse.ok(paymentsService.outstanding());
    }

    /** Send an outstanding-balance reminder (SMS) to a customer. */
    @PostMapping("/reminders")
    @PreAuthorize(WRITE)
    public ApiResponse<Map<String, Object>> remind(@Valid @RequestBody PaymentReminderRequest request) {
        paymentsService.remindCustomer(request.customerId(), request.message());
        return ApiResponse.ok(Map.of("sent", true));
    }
}
