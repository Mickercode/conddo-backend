package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyRefillOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyRefillOfferRepository extends JpaRepository<PharmacyRefillOffer, UUID> {

    List<PharmacyRefillOffer> findByActiveTrueOrderByCreatedAtDesc();

    List<PharmacyRefillOffer> findAllByOrderByCreatedAtDesc();
}
