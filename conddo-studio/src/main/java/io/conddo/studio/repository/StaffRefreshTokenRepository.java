package io.conddo.studio.repository;

import io.conddo.studio.domain.StaffRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StaffRefreshTokenRepository extends JpaRepository<StaffRefreshToken, UUID> {

    Optional<StaffRefreshToken> findByTokenHash(String tokenHash);
}
