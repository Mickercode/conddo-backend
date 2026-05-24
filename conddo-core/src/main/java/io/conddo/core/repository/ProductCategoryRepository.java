package io.conddo.core.repository;

import io.conddo.core.domain.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {

    List<ProductCategory> findAllByOrderByName();

    Optional<ProductCategory> findByName(String name);
}
