package io.conddo.core.repository;

import io.conddo.core.domain.CustomerPrescription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** RLS-scoped; the pharmacist's dashboard pulls the queue for their tenant. */
public interface CustomerPrescriptionRepository extends JpaRepository<CustomerPrescription, UUID> {

    /** All for the bound tenant; pending first then by submission desc. */
    List<CustomerPrescription> findAllByOrderByStatusAscSubmittedAtDesc();

    List<CustomerPrescription> findByStatusOrderBySubmittedAtDesc(String status);
}
