package io.conddo.core.repository;

import io.conddo.core.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/** RLS scopes every query/update to the current tenant — no manual tenant filter. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop50ByOrderByCreatedAtDesc();

    List<Notification> findTop50ByReadFalseOrderByCreatedAtDesc();

    long countByReadFalse();

    /** Marks all of this tenant's unread notifications read in one statement. */
    @Modifying
    @Query("update Notification n set n.read = true where n.read = false")
    int markAllRead();
}
