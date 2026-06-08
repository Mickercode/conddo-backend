package io.conddo.studio.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformTenantSiteRepository extends JpaRepository<PlatformTenantSite, UUID> {

    List<PlatformTenantSite> findAllByOrderByCreatedAtDesc();

    Optional<PlatformTenantSite> findByTenantId(UUID tenantId);

    Optional<PlatformTenantSite> findBySubdomain(String subdomain);

    Optional<PlatformTenantSite> findByCustomDomain(String customDomain);
}
