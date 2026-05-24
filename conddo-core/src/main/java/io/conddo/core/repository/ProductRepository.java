package io.conddo.core.repository;

import io.conddo.core.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * RLS scopes every query to the current tenant. The low-stock query also feeds
 * the dashboard KPI (§11.1): a positive reorder threshold reached by stock.
 */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    @Query("select p from Product p where p.reorderThreshold > 0 and p.stock <= p.reorderThreshold")
    List<Product> findLowStock();

    @Query("select count(p) from Product p where p.reorderThreshold > 0 and p.stock <= p.reorderThreshold")
    long countLowStock();
}
