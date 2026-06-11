package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.BillingPaystackTransaction;
import io.conddo.core.domain.SubscriptionPlan;
import io.conddo.core.domain.TenantSubscription;
import io.conddo.core.domain.User;
import io.conddo.core.paystack.PaystackGateway;
import io.conddo.core.repository.BillingPaystackTransactionRepository;
import io.conddo.core.repository.SubscriptionPlanRepository;
import io.conddo.core.repository.TenantSubscriptionRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Paystack-backed Conddo plan billing (HANDOFF_2026-06-11 §8). Three
 * real flows:
 *
 * <ul>
 *   <li>{@link #checkout} — initialise a Paystack transaction, return
 *       the hosted URL + reference for the FE to redirect to.</li>
 *   <li>{@link #verify} — polled from the return page; reconciles
 *       Paystack's verify response with our row + flips the tenant's
 *       subscription to the new plan on success.</li>
 *   <li>{@link #handleWebhook} — Paystack's authoritative push; same
 *       reconciliation as {@link #verify} but doesn't require a
 *       JWT.</li>
 * </ul>
 */
@Service
public class BillingPaystackService {

    private static final Logger log = LoggerFactory.getLogger(BillingPaystackService.class);

    private static final char[] REF_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final PaystackGateway gateway;
    private final BillingService billingService;
    private final BillingPaystackTransactionRepository transactionRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final UserRepository userRepository;
    private final PharmacyProgramService programService;
    private final TenantSession tenantSession;
    private final Clock clock;
    private final String defaultCallbackUrl;
    private final SecureRandom rng = new SecureRandom();

    public BillingPaystackService(PaystackGateway gateway,
                                  BillingService billingService,
                                  BillingPaystackTransactionRepository transactionRepository,
                                  TenantSubscriptionRepository subscriptionRepository,
                                  SubscriptionPlanRepository planRepository,
                                  UserRepository userRepository,
                                  PharmacyProgramService programService,
                                  TenantSession tenantSession,
                                  Clock clock,
                                  @Value("${conddo.paystack.callback-url:https://app.conddo.io/settings/billing/return}")
                                  String defaultCallbackUrl) {
        this.gateway = gateway;
        this.billingService = billingService;
        this.transactionRepository = transactionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.programService = programService;
        this.tenantSession = tenantSession;
        this.clock = clock;
        this.defaultCallbackUrl = defaultCallbackUrl;
    }

    @Transactional
    public CheckoutResult checkout(String planName, String billingCycle, UUID userId) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        SubscriptionPlan plan = planRepository.findByName(normalisePlan(planName))
                .orElseThrow(() -> new NotFoundException("Plan not found: " + planName));
        String cycle = normaliseCycle(billingCycle);
        long amountKobo = computeAmountKobo(plan, cycle);
        if (amountKobo <= 0) {
            throw new IllegalArgumentException(
                    "Plan " + plan.getName() + " has no Paystack-billed price configured");
        }
        User user = userRepository.findById(userId).orElse(null);
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Caller has no email on file for Paystack");
        }
        String reference = generateReference(tenantId);
        BillingPaystackTransaction tx = transactionRepository.save(new BillingPaystackTransaction(
                tenantId, reference, plan.getId(), cycle, amountKobo, userId));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", tenantId.toString());
        metadata.put("planId", plan.getId().toString());
        metadata.put("planName", plan.getName());
        metadata.put("billingCycle", cycle);

        PaystackGateway.InitResult init = gateway.initialize(email, amountKobo, reference,
                defaultCallbackUrl, metadata);
        return new CheckoutResult(init.authorizationUrl(), init.reference(),
                init.accessCode(), tx);
    }

    @Transactional
    public VerifyResult verify(String reference) {
        tenantSession.bind();
        BillingPaystackTransaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new NotFoundException("Transaction not found"));
        // Already-terminal: don't re-call Paystack.
        if (!BillingPaystackTransaction.STATUS_PENDING.equals(tx.getStatus())) {
            return new VerifyResult(tx, currentActiveSubscription(tx.getTenantId()));
        }
        PaystackGateway.VerifyResult v = gateway.verify(reference);
        applyOutcome(tx, v);
        return new VerifyResult(tx, currentActiveSubscription(tx.getTenantId()));
    }

    /**
     * Cross-tenant webhook handler — the request has no JWT. The
     * caller (controller) has already verified the HMAC signature.
     */
    @Transactional
    public void handleWebhook(String eventType, JsonLike payload) {
        tenantSession.bindCrossTenant();
        String reference = payload.string("data.reference");
        if (reference == null || reference.isBlank()) {
            log.debug("Paystack webhook {} has no reference — ignored", eventType);
            return;
        }
        BillingPaystackTransaction tx = transactionRepository.findByReference(reference).orElse(null);
        if (tx == null) {
            log.warn("Paystack webhook references unknown tx {}", reference);
            return;
        }
        switch (eventType) {
            case "charge.success" -> applyOutcomeFromWebhook(tx, payload,
                    PaystackGateway.VerifyResult.Status.SUCCESS);
            case "charge.failed" -> applyOutcomeFromWebhook(tx, payload,
                    PaystackGateway.VerifyResult.Status.FAILED);
            case "subscription.create" -> {
                String subCode = payload.string("data.subscription_code");
                if (subCode != null && !subCode.isBlank()) {
                    subscriptionRepository.findActiveByTenantId(tx.getTenantId())
                            .ifPresent(sub -> {
                                sub.setPaystackSubscriptionCode(subCode);
                                subscriptionRepository.save(sub);
                            });
                }
            }
            case "subscription.disable", "invoice.payment_failed" -> log.info(
                    "Paystack webhook {} for tx {} noted; existing cron drives grace/expired",
                    eventType, reference);
            default -> log.debug("Paystack webhook {} unhandled", eventType);
        }
    }

    /**
     * Cancel the Paystack-side subscription so renewals stop. The
     * local tenant_subscriptions row stays active until expires_at
     * per the existing BillingService.cancel contract.
     */
    @Transactional
    public TenantSubscription cancel() {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        TenantSubscription sub = subscriptionRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No active subscription to cancel for tenant " + tenantId));
        if (sub.getPaystackSubscriptionCode() != null) {
            try {
                gateway.disableSubscription(sub.getPaystackSubscriptionCode(), null);
            } catch (RuntimeException ex) {
                log.warn("Paystack subscription disable failed for {}: {}",
                        sub.getPaystackSubscriptionCode(), ex.getMessage());
            }
        }
        return billingService.cancel(tenantId);
    }

    // ----- internals ---------------------------------------------------------

    private void applyOutcome(BillingPaystackTransaction tx, PaystackGateway.VerifyResult v) {
        switch (v.status()) {
            case SUCCESS -> {
                tx.markSuccess(v.paidAt() == null ? OffsetDateTime.now(clock) : v.paidAt(),
                        v.subscriptionCode());
                transactionRepository.save(tx);
                if (BillingPaystackTransaction.PURPOSE_PROGRAM_ENROLLMENT.equals(tx.getPurpose())
                        && tx.getEnrollmentId() != null) {
                    programService.activateEnrollment(tx.getEnrollmentId(), v.subscriptionCode());
                } else {
                    activatePlan(tx);
                }
            }
            case FAILED -> {
                tx.markFailed(v.failureReason());
                transactionRepository.save(tx);
            }
            case ABANDONED -> {
                tx.markAbandoned();
                transactionRepository.save(tx);
            }
            default -> {
                // PENDING — leave as-is; FE polls again.
            }
        }
    }

    private void applyOutcomeFromWebhook(BillingPaystackTransaction tx, JsonLike payload,
                                          PaystackGateway.VerifyResult.Status status) {
        OffsetDateTime paidAt = parseInstant(payload.string("data.paid_at"));
        String subCode = payload.string("data.authorization.authorization_code");
        String failureReason = payload.string("data.gateway_response");
        applyOutcome(tx, new PaystackGateway.VerifyResult(
                tx.getReference(), status, null, paidAt, failureReason, subCode, null));
    }

    private void activatePlan(BillingPaystackTransaction tx) {
        // Reuse BillingService.upgrade for the atomic plan flip; the
        // method handles the partial unique index + carry-over of
        // remaining trial time. NB the signature is (tenant, planName,
        // billingCycle) — easy to get wrong.
        billingService.upgrade(tx.getTenantId(), planNameFor(tx.getPlanId()),
                tx.getBillingCycle());
    }

    private TenantSubscription currentActiveSubscription(UUID tenantId) {
        return subscriptionRepository.findActiveByTenantId(tenantId).orElse(null);
    }

    private String planNameFor(UUID planId) {
        return planRepository.findById(planId)
                .map(SubscriptionPlan::getName)
                .orElseThrow(() -> new NotFoundException("Plan vanished: " + planId));
    }

    private long computeAmountKobo(SubscriptionPlan plan, String cycle) {
        if (plan.getMonthlyPrice() == null) {
            return 0L;
        }
        long monthlyKobo = plan.getMonthlyPrice();
        return "quarterly".equals(cycle) ? monthlyKobo * 3 : monthlyKobo;
    }

    private String generateReference(UUID tenantId) {
        StringBuilder sb = new StringBuilder("CONDDO_");
        for (int i = 0; i < 12; i++) {
            sb.append(REF_ALPHABET[rng.nextInt(REF_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String normalisePlan(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static String normaliseCycle(String cycle) {
        if (cycle == null || cycle.isBlank()) {
            return "monthly";
        }
        String c = cycle.trim().toLowerCase();
        return "quarterly".equals(c) ? "quarterly" : "monthly";
    }

    private static OffsetDateTime parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ----- DTOs --------------------------------------------------------------

    public record CheckoutResult(String authorizationUrl, String reference,
                                 String accessCode, BillingPaystackTransaction transaction) {
    }

    public record VerifyResult(BillingPaystackTransaction transaction,
                               TenantSubscription subscription) {
    }

    /** Minimal JSON accessor for the webhook payload — keeps the service shape tidy. */
    public interface JsonLike {
        String string(String dottedPath);
    }
}
