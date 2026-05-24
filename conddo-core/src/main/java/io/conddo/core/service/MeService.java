package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves the current dashboard identity — the authenticated tenant user plus
 * their tenant — for {@code GET /api/v1/me} (the dashboard shell: sidebar
 * business name, user name/role, subdomain). The user is loaded under RLS, so it
 * is always the caller's own record within their tenant.
 */
@Service
public class MeService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;

    public MeService(UserRepository userRepository, TenantRepository tenantRepository,
                     TenantSession tenantSession) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public Identity current(UUID userId) {
        tenantSession.bind();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Current user not found"));
        Tenant tenant = tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
        return new Identity(user, tenant);
    }

    public record Identity(User user, Tenant tenant) {
    }
}
