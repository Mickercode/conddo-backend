package io.conddo.core.repository;

import io.conddo.core.domain.FashionOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * RLS scopes every query to the current tenant. Fashion-specific order repository.
 */
public interface FashionOrderRepository extends JpaRepository<FashionOrder, UUID>, JpaSpecificationExecutor<FashionOrder> {

    @Query("select fo from FashionOrder fo where fo.stage = :stage")
    List<FashionOrder> findByStage(String stage);

    @Query("select fo from FashionOrder fo where fo.customerId = :customerId")
    List<FashionOrder> findByCustomerId(UUID customerId);

    /** Search by customer name or reference. */
    @Query("select fo from FashionOrder fo where lower(fo.customerName) like lower(concat('%', :search, '%')) or lower(fo.reference) like lower(concat('%', :search, '%'))")
    List<FashionOrder> search(String search);

    /** Find by reference (unique per tenant). */
    java.util.Optional<FashionOrder> findByReference(String reference);
}
