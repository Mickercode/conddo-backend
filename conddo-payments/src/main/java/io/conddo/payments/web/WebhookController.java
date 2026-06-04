package io.conddo.payments.web;

import io.conddo.payments.common.ApiResponse;
import io.conddo.payments.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * RoutePay webhook entry point. No JWT / service-token — authentication is the
 * HMAC signature on {@code X-RoutePay-Signature}.
 *
 * <p>Always returns 200 to RoutePay so they don't retry on transient errors.
 * The response body indicates whether we actually processed the event (the
 * RoutePay merchant dashboard surfaces it in their delivery logs).
 */
@RestController
@RequestMapping("/api/payments/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentService paymentService;

    public WebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/routepay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> routepay(
            HttpServletRequest request,
            @RequestHeader(value = "X-RoutePay-Signature", required = false) String signature)
            throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        PaymentService.WebhookResult result = paymentService.handleWebhook(body, signature);
        if (!result.processed()) {
            log.info("RoutePay webhook rejected: {} (sig {})", result.reason(),
                    signature == null ? "missing" : "present");
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "processed", result.processed(),
                "reason", result.reason() == null ? "OK" : result.reason())));
    }
}
