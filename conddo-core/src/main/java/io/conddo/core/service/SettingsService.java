package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Tenant settings (§11.11): business profile, branding, social handles,
 * location, business hours, and notification preferences — all tenant-level
 * config on the tenant row. Industry (vertical) and subdomain (slug) are
 * read-only here. Every method binds the tenant first (RLS); the tenant is
 * always the caller's own.
 */
@Service
public class SettingsService {

    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;

    public SettingsService(TenantRepository tenantRepository, TenantSession tenantSession) {
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public Tenant businessProfile() {
        tenantSession.bind();
        return requireTenant();
    }

    @Transactional
    public Tenant updateBusinessProfile(String name, String tagline, String description,
                                        String email, String phone) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        if (name != null) {
            tenant.rename(name);
        }
        if (tagline != null) {
            tenant.setTagline(tagline);
        }
        if (description != null) {
            tenant.setDescription(description);
        }
        if (email != null) {
            tenant.setContactEmail(email);
        }
        if (phone != null) {
            tenant.setContactPhone(phone);
        }
        return tenantRepository.save(tenant);
    }

    @Transactional
    public Tenant updateBranding(String primaryColor, String logoUrl) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        if (primaryColor != null) {
            tenant.setPrimaryColor(primaryColor);
        }
        if (logoUrl != null) {
            tenant.setLogoUrl(logoUrl);
        }
        return tenantRepository.save(tenant);
    }

    @Transactional
    public Map<String, Object> updateSocialHandles(Map<String, Object> handles) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        tenant.setSocialHandles(handles);
        return orEmpty(tenantRepository.save(tenant).getSocialHandles());
    }

    @Transactional
    public Map<String, Object> updateLocation(Map<String, Object> location) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        tenant.setLocation(location);
        return orEmpty(tenantRepository.save(tenant).getLocation());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> businessHours() {
        tenantSession.bind();
        return orEmpty(requireTenant().getBusinessHours());
    }

    @Transactional
    public Map<String, Object> updateBusinessHours(Map<String, Object> hours) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        tenant.setBusinessHours(hours);
        return orEmpty(tenantRepository.save(tenant).getBusinessHours());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> notifications() {
        tenantSession.bind();
        return orEmpty(requireTenant().getNotificationPrefs());
    }

    @Transactional
    public Map<String, Object> updateNotifications(Map<String, Object> prefs) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        tenant.setNotificationPrefs(prefs);
        return orEmpty(tenantRepository.save(tenant).getNotificationPrefs());
    }

    /** Danger Zone: deactivate the tenant (status → INACTIVE). */
    @Transactional
    public Tenant deactivate() {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        tenant.deactivate();
        return tenantRepository.save(tenant);
    }

    private Tenant requireTenant() {
        return tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }
}
