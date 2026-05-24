package io.conddo.core.service;

import io.conddo.core.audit.AuditActions;
import io.conddo.core.audit.AuditService;
import io.conddo.core.domain.Customer;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped CRM operations. Each method binds the tenant to the
 * transaction first ({@link TenantSession#bind()}), then works as if the
 * database contained only this tenant's data — because, under RLS, it does.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final TenantSession tenantSession;
    private final AuditService auditService;

    public CustomerService(CustomerRepository customerRepository, TenantSession tenantSession,
                           AuditService auditService) {
        this.customerRepository = customerRepository;
        this.tenantSession = tenantSession;
        this.auditService = auditService;
    }

    @Transactional
    public Customer create(String fullName, String email, String phone, String notes) {
        tenantSession.bind();
        Customer customer = customerRepository.save(new Customer(TenantContext.require(), fullName, email, phone, notes));
        auditService.record(AuditActions.CUSTOMER_CREATED, "CUSTOMER", customer.getId(),
                null, Map.of("fullName", customer.getFullName()));
        return customer;
    }

    @Transactional(readOnly = true)
    public List<Customer> findAll() {
        tenantSession.bind();
        return customerRepository.findAll();
    }
}
