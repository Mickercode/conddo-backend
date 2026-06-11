package io.conddo.core.repository;

import io.conddo.core.domain.PosPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PosPaymentRepository extends JpaRepository<PosPayment, UUID> {

    List<PosPayment> findBySaleIdOrderByPaidAtAsc(UUID saleId);

    void deleteBySaleIdAndId(UUID saleId, UUID id);
}
