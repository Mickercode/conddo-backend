package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyRefillOfferClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PharmacyRefillOfferClaimRepository extends JpaRepository<PharmacyRefillOfferClaim, UUID> {

    Optional<PharmacyRefillOfferClaim> findByOfferCode(String offerCode);
}
