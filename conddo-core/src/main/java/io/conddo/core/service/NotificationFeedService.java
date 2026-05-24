package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Notification;
import io.conddo.core.repository.NotificationRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The in-app notifications bell feed (§11.12). Tenant-scoped (RLS). Producers in
 * other modules call {@link #create} to enqueue a notice (e.g. a public
 * self-booking notifies the owner); the topbar reads {@link #feed} and marks
 * items read.
 */
@Service
public class NotificationFeedService {

    private final NotificationRepository notificationRepository;
    private final TenantSession tenantSession;

    public NotificationFeedService(NotificationRepository notificationRepository, TenantSession tenantSession) {
        this.notificationRepository = notificationRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public Feed feed(boolean unreadOnly) {
        tenantSession.bind();
        List<Notification> items = unreadOnly
                ? notificationRepository.findTop50ByReadFalseOrderByCreatedAtDesc()
                : notificationRepository.findTop50ByOrderByCreatedAtDesc();
        return new Feed(items, notificationRepository.countByReadFalse());
    }

    @Transactional
    public void markRead(UUID id) {
        tenantSession.bind();
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        notification.markRead();
        notificationRepository.save(notification);
    }

    @Transactional
    public void markAllRead() {
        tenantSession.bind();
        notificationRepository.markAllRead();
    }

    /**
     * Records a notification for the current tenant. Intended for in-process
     * producers, so it runs within the caller's already-bound transaction.
     */
    @Transactional
    public Notification create(String type, String title, String body, UUID userId) {
        tenantSession.bind();
        return notificationRepository.save(
                new Notification(TenantContext.require(), userId, type, title, body));
    }

    /** The bell feed: recent notifications + the current unread count. */
    public record Feed(List<Notification> items, long unread) {
    }
}
