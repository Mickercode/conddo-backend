package io.conddo.api.billing;

import io.conddo.core.service.BillingService;
import io.conddo.core.service.BillingService.TransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hourly walk of every subscription past its {@code expires_at}
 * (BILLING_TIERS_SPEC §6 / merchant-readiness slice 3). Fires the
 * trialing/active → grace and grace → expired transitions, then delegates
 * to {@link BillingExpiryNotifier} per transitioned subscription.
 *
 * <p>The transition itself happens inside {@link BillingService#runExpiryScan}
 * in its own transaction so the state change is durable before the
 * notifications go out. The per-row notify is split into a separate component
 * so its {@code @Transactional(REQUIRES_NEW)} boundary works (self-invocation
 * would bypass the proxy).
 *
 * <p>Schedule is configurable for tests. The default {@code 0 0 * * * *}
 * fires at minute 0 of every hour; tests pin {@code conddo.billing.expiry-cron}
 * to a far-future expression and call {@link #runOnce()} directly.
 */
@Component
public class BillingExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingExpiryScheduler.class);

    private final BillingService billingService;
    private final BillingExpiryNotifier notifier;

    public BillingExpiryScheduler(BillingService billingService, BillingExpiryNotifier notifier) {
        this.billingService = billingService;
        this.notifier = notifier;
    }

    @Scheduled(cron = "${conddo.billing.expiry-cron:0 0 * * * *}", zone = "UTC")
    public void runOnce() {
        try {
            List<TransitionResult> transitions = billingService.runExpiryScan();
            if (transitions.isEmpty()) {
                return;
            }
            log.info("Subscription expiry scan: {} transitions", transitions.size());
            for (TransitionResult t : transitions) {
                try {
                    notifier.notify(t);
                } catch (RuntimeException ex) {
                    log.error("Plan-transition notify failed for tenant {} ({} → {}): {}",
                            t.tenantId(), t.fromStatus(), t.toStatus(), ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            log.error("Subscription expiry scan failed: {}", ex.getMessage());
        }
    }
}
