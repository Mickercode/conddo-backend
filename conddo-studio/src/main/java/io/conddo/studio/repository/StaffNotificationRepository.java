package io.conddo.studio.repository;

import io.conddo.studio.domain.StaffNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffNotificationRepository extends JpaRepository<StaffNotification, UUID> {

    List<StaffNotification> findTop50ByStaffIdOrderByCreatedAtDesc(UUID staffId);

    List<StaffNotification> findTop50ByStaffIdAndReadFalseOrderByCreatedAtDesc(UUID staffId);

    long countByStaffIdAndReadFalse(UUID staffId);

    Optional<StaffNotification> findByIdAndStaffId(UUID id, UUID staffId);
}
