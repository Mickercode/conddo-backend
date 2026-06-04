package io.conddo.payments.repository;

import io.conddo.payments.domain.TenantAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantAccountRepository extends JpaRepository<TenantAccount, UUID> {
}
