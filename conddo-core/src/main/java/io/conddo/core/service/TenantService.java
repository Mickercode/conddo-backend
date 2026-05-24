package io.conddo.core.service;

import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.auth.Role;
import io.conddo.core.common.Slugs;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tenant provisioning (signup). Operates on the un-scoped {@code tenants} table
 * to create the business, then provisions its first administrator. Listing all
 * tenants is a super-admin concern; exposed here for the demo.
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantSession tenantSession;
    private final PasswordHasher passwordHasher;

    public TenantService(TenantRepository tenantRepository, UserRepository userRepository,
                         TenantSession tenantSession, PasswordHasher passwordHasher) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.tenantSession = tenantSession;
        this.passwordHasher = passwordHasher;
    }

    /**
     * Creates a tenant and its first administrator in one transaction. The
     * {@code users} table is RLS-scoped, so once the tenant exists we bind it to
     * this transaction before inserting the admin — the row's tenant_id then
     * satisfies the {@code tenant_isolation} WITH CHECK clause.
     */
    @Transactional
    public Tenant create(String name, String slug, String verticalId, String planId,
                         String adminEmail, String adminPassword, String adminFullName) {
        if (tenantRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("A tenant with slug '" + slug + "' already exists");
        }
        Tenant tenant = tenantRepository.save(new Tenant(name, slug, verticalId, planId));
        persistAdmin(tenant, adminEmail, passwordHasher.hash(adminPassword), adminFullName, null, false);
        return tenant;
    }

    /**
     * Creates a tenant + its admin from a completed (phone-verified) signup:
     * auto-generates a unique slug from the business name, reuses the already-
     * hashed password, and marks the admin's phone verified. Returns both so the
     * caller can issue tokens.
     */
    @Transactional
    public Provisioned provisionFromRegistration(String businessName, String verticalId, String planId,
                                                 String adminEmail, String adminPasswordHash,
                                                 String adminFullName, String adminPhone) {
        Tenant tenant = tenantRepository.save(
                new Tenant(businessName, uniqueSlug(businessName), verticalId, planId));
        User admin = persistAdmin(tenant, adminEmail, adminPasswordHash, adminFullName, adminPhone, true);
        return new Provisioned(tenant, admin);
    }

    /** Cross-tenant listing is a platform-staff concern (PRD §6.2). */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public List<Tenant> findAll() {
        return tenantRepository.findAll();
    }

    /**
     * Inserts the tenant's admin. {@code users} is RLS-scoped, so we bind the
     * just-created tenant to the transaction first — the row's tenant_id then
     * satisfies the {@code tenant_isolation} WITH CHECK clause.
     */
    private User persistAdmin(Tenant tenant, String email, String passwordHash,
                              String fullName, String phone, boolean phoneVerified) {
        TenantContext.set(tenant.getId());
        tenantSession.bind();
        User admin = new User(tenant.getId(), email, passwordHash, fullName, Role.TENANT_ADMIN.name(), phone);
        if (phoneVerified) {
            admin.markPhoneVerified();
        }
        return userRepository.save(admin);
    }

    /** Slugifies the business name and de-duplicates with a numeric suffix. */
    private String uniqueSlug(String businessName) {
        String base = Slugs.from(businessName);
        String slug = base;
        int suffix = 2;
        while (tenantRepository.existsBySlug(slug)) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }

    /** A newly provisioned tenant together with its admin user. */
    public record Provisioned(Tenant tenant, User admin) {
    }
}
