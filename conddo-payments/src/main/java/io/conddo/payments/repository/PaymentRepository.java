package io.conddo.payments.repository;

import io.conddo.payments.domain.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByRoutepayReference(String routepayReference);

    Optional<Payment> findByRoutepayTransactionRef(String routepayTransactionRef);

    /** Tenant-scoped list — paged by created_at desc for the FE history view. */
    Page<Payment> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
