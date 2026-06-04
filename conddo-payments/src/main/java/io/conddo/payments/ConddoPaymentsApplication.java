package io.conddo.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Conddo Payments — standalone web service (ACTION_LIST §7a). Lives in its
 * own deployment so a platform API outage can't kill in-flight transactions,
 * and a payments outage can't kill signups.
 *
 * <p>Authenticates inbound traffic two ways:
 * <ul>
 *   <li>Tenant Bearer JWT — RSA-signed by conddo-api, verified here using the
 *       same public key (same issuer, same {@code tenant_id} claim).</li>
 *   <li>{@code X-Payments-Service-Token} — shared secret for service-to-service
 *       calls (conddo-api → payments on signup + the {@code /internal/*} surface).</li>
 * </ul>
 * RoutePay webhooks are HMAC-signature-verified, no Bearer.
 */
@SpringBootApplication
@EnableAsync
public class ConddoPaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConddoPaymentsApplication.class, args);
    }
}
