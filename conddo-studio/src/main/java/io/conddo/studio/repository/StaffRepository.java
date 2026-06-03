package io.conddo.studio.repository;

import io.conddo.studio.domain.Staff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {

    Optional<Staff> findByEmail(String email);

    List<Staff> findAllByOrderByFullName();

    /** Active staff in a role — used to fan-out escalation emails to TEAM_LEAD / ADMIN. */
    List<Staff> findByRoleAndActiveTrue(String role);
}
