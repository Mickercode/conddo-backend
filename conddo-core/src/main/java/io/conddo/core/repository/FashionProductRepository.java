package io.conddo.core.repository;

import io.conddo.core.domain.FashionProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * RLS scopes every query to the current tenant. Fashion-specific product repository
 * with size/color/material variant support.
 */
public interface FashionProductRepository extends JpaRepository<FashionProduct, UUID>, JpaSpecificationExecutor<FashionProduct> {

    @Query("select fp from FashionProduct fp where fp.active = true")
    List<FashionProduct> findActive();

    @Query("select fp from FashionProduct fp where fp.category = :category")
    List<FashionProduct> findByCategory(String category);

    @Query("select fp from FashionProduct fp where fp.material = :material")
    List<FashionProduct> findByMaterial(String material);

    /** Find products with low stock (any variant < 5). */
    @Query("select fp from FashionProduct fp where fp.totalStock < 10")
    List<FashionProduct> findLowStock();

    @Query("select count(fp) from FashionProduct fp where fp.totalStock < 10")
    long countLowStock();

    /** Search by name or SKU. */
    @Query("select fp from FashionProduct fp where lower(fp.name) like lower(concat('%', :search, '%')) or lower(fp.sku) like lower(concat('%', :search, '%'))")
    List<FashionProduct> search(String search);

    /** Find by SKU (unique per tenant). */
    java.util.Optional<FashionProduct> findBySku(String sku);
}
