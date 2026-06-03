package io.conddo.studio.repository;

import io.conddo.studio.domain.StaffNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffNotificationRepository extends JpaRepository<StaffNotification, UUID> {

    List<StaffNotification> findTop50ByStaffIdOrderByCreatedAtDesc(UUID staffId);

    List<StaffNotification> findTop50ByStaffIdAndReadFalseOrderByCreatedAtDesc(UUID staffId);

    long countByStaffIdAndReadFalse(UUID staffId);

    Optional<StaffNotification> findByIdAndStaffId(UUID id, UUID staffId);

    /**
     * Bulk-mark a staff member's unread notifications as read. Returns the count
     * marked so the caller can surface "5 marked read" feedback without a re-fetch.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StaffNotification n set n.read = true where n.staffId = :staffId and n.read = false")
    int markAllRead(@Param("staffId") UUID staffId);
}
