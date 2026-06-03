package io.conddo.studio.notifications;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.StaffNotification;
import io.conddo.studio.repository.StaffNotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Staff in-app notifications feed (Infrastructure §13.2). */
@Service
public class StudioNotificationService {

    private final StaffNotificationRepository repository;

    public StudioNotificationService(StaffNotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<StaffNotification> list(UUID staffId, boolean unreadOnly) {
        return unreadOnly
                ? repository.findTop50ByStaffIdAndReadFalseOrderByCreatedAtDesc(staffId)
                : repository.findTop50ByStaffIdOrderByCreatedAtDesc(staffId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID staffId) {
        return repository.countByStaffIdAndReadFalse(staffId);
    }

    @Transactional
    public void markRead(UUID id, UUID staffId) {
        StaffNotification notification = repository.findByIdAndStaffId(id, staffId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        notification.markRead();
        repository.save(notification);
    }

    /** Mark every unread notification for a staff member as read. Returns how many were updated. */
    @Transactional
    public int markAllRead(UUID staffId) {
        return repository.markAllRead(staffId);
    }
}
