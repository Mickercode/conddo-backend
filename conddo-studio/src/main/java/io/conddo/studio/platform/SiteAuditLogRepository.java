package io.conddo.studio.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SiteAuditLogRepository extends JpaRepository<SiteAuditLog, UUID> {

    List<SiteAuditLog> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
}
