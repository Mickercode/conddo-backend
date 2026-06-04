package io.conddo.api.signup;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.signup.TenantActivatedEvent;
import io.conddo.core.signup.WebsiteTypeRecommendation;
import io.conddo.core.signup.WebsiteTypeResolver;
import io.conddo.core.studio.StudioJobGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Closes the signup → Studio loop: after a tenant commits, resolve which kind of
 * website they need ({@link WebsiteTypeResolver}) and hand a {@code WEBSITE_BUILD}
 * job to Studio via the existing {@link StudioJobGateway} intake seam.
 *
 * <p>Runs <b>after commit</b> so a flaky Studio call never rolls back signup. The
 * gateway is already fail-safe (returns empty on errors / unconfigured), but we
 * also catch everything else here — signup must never break because of Studio.
 */
@Component
public class TenantActivationListener {

    private static final Logger log = LoggerFactory.getLogger(TenantActivationListener.class);

    private final TenantRepository tenantRepository;
    private final WebsiteTypeResolver websiteTypeResolver;
    private final StudioJobGateway studioJobGateway;
    private final PaymentsGateway paymentsGateway;

    public TenantActivationListener(TenantRepository tenantRepository,
                                    WebsiteTypeResolver websiteTypeResolver,
                                    StudioJobGateway studioJobGateway,
                                    PaymentsGateway paymentsGateway) {
        this.tenantRepository = tenantRepository;
        this.websiteTypeResolver = websiteTypeResolver;
        this.studioJobGateway = studioJobGateway;
        this.paymentsGateway = paymentsGateway;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onTenantActivated(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));

            WebsiteTypeRecommendation rec = websiteTypeResolver.resolve(
                    tenant.getVerticalId(), tenant.getPlanId());

            Map<String, Object> brief = briefFor(tenant, rec);
            String title = "Website Build — " + tenant.getName();
            studioJobGateway.createJob(tenant.getId(), "WEBSITE_BUILD", title, brief)
                    .ifPresent(ref -> log.info("Auto-created Studio job {} ({}) for tenant {} — {}",
                            ref.jobNumber(), rec.type(), tenant.getId(), rec.reasoning()));
        } catch (RuntimeException ex) {
            // Signup is already committed — never let a downstream failure surface.
            log.error("Auto-create Studio job failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        }
    }

    /**
     * Second handler on the same event (§7a): provision the tenant's RoutePay
     * sub-account through conddo-payments. Separate listener method so a Studio
     * outage doesn't suppress payments provisioning and vice versa — each
     * gateway is independently fail-safe.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onTenantActivated_provisionPayments(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            String contactEmail = tenant.getContactEmail() == null
                    ? "owner+" + tenant.getSlug() + "@conddo.io"
                    : tenant.getContactEmail();
            paymentsGateway.provisionTenantAccount(tenant.getId(), tenant.getSlug(),
                            tenant.getName(), contactEmail)
                    .ifPresent(account -> log.info("Provisioned payments sub-account for tenant {} — {} ({})",
                            tenant.getId(), account.subaccountId(), account.status()));
        } catch (RuntimeException ex) {
            log.error("Auto-provision payments failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        }
    }

    private Map<String, Object> briefFor(Tenant tenant, WebsiteTypeRecommendation rec) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("source", "tenant-activated");
        brief.put("tenantId", tenant.getId().toString());
        brief.put("tenantSlug", tenant.getSlug());
        brief.put("businessName", tenant.getName());
        brief.put("vertical", tenant.getVerticalId());
        brief.put("plan", tenant.getPlanId());
        brief.put("websiteType", rec.type().name());
        brief.put("recommendedSections", rec.sections());
        brief.put("typeReasoning", rec.reasoning());
        if (tenant.getContactEmail() != null) {
            brief.put("contactEmail", tenant.getContactEmail());
        }
        if (tenant.getContactPhone() != null) {
            brief.put("contactPhone", tenant.getContactPhone());
        }
        return brief;
    }
}
