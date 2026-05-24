package io.conddo.core.service;

import io.conddo.core.audit.AuditActions;
import io.conddo.core.audit.AuditService;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRM (§11.3). Every method binds the tenant first, so RLS scopes
 * all reads/writes/searches/counts to the caller's tenant automatically — no
 * manual {@code WHERE tenant_id}. Search/segment filtering uses JPA
 * Specifications, which RLS still scopes at the DB level.
 */
@Service
public class CustomerService {

    /** Lifetime-spend threshold for the "High value" segment (₦). */
    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("50000");
    private static final int INACTIVE_DAYS = 30;

    private final CustomerRepository customerRepository;
    private final TenantSession tenantSession;
    private final AuditService auditService;
    private final Clock clock;

    public CustomerService(CustomerRepository customerRepository, TenantSession tenantSession,
                           AuditService auditService, Clock clock) {
        this.customerRepository = customerRepository;
        this.tenantSession = tenantSession;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Customer create(String fullName, String email, String phone, String notes) {
        tenantSession.bind();
        Customer customer = customerRepository.save(
                new Customer(TenantContext.require(), fullName, email, phone, notes));
        auditService.record(AuditActions.CUSTOMER_CREATED, "CUSTOMER", customer.getId(),
                null, Map.of("fullName", customer.getFullName()));
        return customer;
    }

    @Transactional(readOnly = true)
    public Page<Customer> list(String search, String filter, Pageable pageable) {
        tenantSession.bind();
        return customerRepository.findAll(filteredBy(search, filter), pageable);
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        tenantSession.bind();
        return require(id);
    }

    @Transactional
    public Customer update(UUID id, String fullName, String email, String phone) {
        tenantSession.bind();
        Customer customer = require(id);
        customer.rename(fullName);
        if (email != null) {
            customer.setEmail(email);
        }
        if (phone != null) {
            customer.setPhone(phone);
        }
        return customerRepository.save(customer);
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        customerRepository.delete(require(id));
    }

    @Transactional
    public Customer setNotes(UUID id, String notes) {
        tenantSession.bind();
        Customer customer = require(id);
        customer.setNotes(notes);
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer setMeasurements(UUID id, Map<String, Object> measurements) {
        tenantSession.bind();
        Customer customer = require(id);
        customer.setMeasurements(measurements);
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer addTag(UUID id, String tag) {
        tenantSession.bind();
        Customer customer = require(id);
        customer.addTag(tag);
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer removeTag(UUID id, String tag) {
        tenantSession.bind();
        Customer customer = require(id);
        customer.removeTag(tag);
        return customerRepository.save(customer);
    }

    @Transactional(readOnly = true)
    public List<Segment> segments() {
        tenantSession.bind();
        List<Segment> segments = new ArrayList<>();
        segments.add(segment("all", "All"));
        segments.add(segment("new", "New this month"));
        segments.add(segment("high_value", "High value"));
        segments.add(segment("inactive", "Inactive"));
        return segments;
    }

    private Segment segment(String key, String label) {
        return new Segment(key, label, customerRepository.count(filteredBy(null, key)));
    }

    private Customer require(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    private Specification<Customer> filteredBy(String search, String filter) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("email"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("phone"), "")), like)));
            }
            if (filter != null) {
                switch (filter) {
                    case "new" -> predicates.add(cb.greaterThanOrEqualTo(
                            root.get("createdAt"),
                            now.toLocalDate().withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
                    case "high_value" -> predicates.add(cb.greaterThanOrEqualTo(
                            root.get("totalSpent"), HIGH_VALUE_THRESHOLD));
                    case "inactive" -> predicates.add(cb.or(
                            cb.isNull(root.get("lastActive")),
                            cb.lessThan(root.get("lastActive"), now.minus(INACTIVE_DAYS, ChronoUnit.DAYS))));
                    default -> {
                        // "all" or unknown → no extra predicate
                    }
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** A customer segment with its current member count. */
    public record Segment(String key, String label, long count) {
    }
}
