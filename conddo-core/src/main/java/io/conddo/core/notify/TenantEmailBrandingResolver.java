package io.conddo.core.notify;

import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up the per-tenant email branding (V52) for the current
 * request, if a tenant is bound. {@link BrevoEmailSender} calls
 * this on every send so order confirmations / booking notifications
 * / staff invites etc. show the tenant's brand instead of "Conddo".
 *
 * <p>No-op when no tenant is bound (signup OTP, password reset for
 * unknown email) — those flows keep the global default.
 */
@Component
public class TenantEmailBrandingResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantEmailBrandingResolver.class);

    private final TenantRepository tenantRepository;

    public TenantEmailBrandingResolver(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public Optional<TenantEmailBranding> currentBranding() {
        Optional<UUID> tenantId = TenantContext.get();
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }
        try {
            return tenantRepository.findById(tenantId.get())
                    .map(t -> new TenantEmailBranding(
                            t.effectiveEmailFromName(),
                            t.effectiveEmailReplyTo()));
        } catch (RuntimeException ex) {
            // Never let a branding lookup fail an actual email send.
            log.debug("Tenant branding lookup failed for {}: {}", tenantId.get(), ex.getMessage());
            return Optional.empty();
        }
    }
}
