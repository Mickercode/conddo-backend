package io.conddo.core.service;

import io.conddo.core.domain.Booking;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Order;
import io.conddo.core.repository.BookingRepository;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Global topbar search (§11.12) across customers, orders, and bookings — all
 * RLS-scoped to the current tenant. Returns a small set of hits per type;
 * matching is a case-insensitive contains on the obvious display fields.
 */
@Service
public class SearchService {

    private static final Pageable TOP_5 = PageRequest.of(0, 5);

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final BookingRepository bookingRepository;
    private final TenantSession tenantSession;

    public SearchService(CustomerRepository customerRepository, OrderRepository orderRepository,
                         BookingRepository bookingRepository, TenantSession tenantSession) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.bookingRepository = bookingRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public Results search(String query) {
        tenantSession.bind();
        if (query == null || query.isBlank()) {
            return new Results(List.of(), List.of(), List.of());
        }
        String like = "%" + query.trim().toLowerCase() + "%";

        List<Hit> customers = customerRepository.findAll(customerMatch(like), TOP_5).stream()
                .map(c -> new Hit(c.getId(), c.getFullName(), c.getPhone())).toList();
        List<Hit> orders = orderRepository.findAll(orderMatch(like), TOP_5).stream()
                .map(o -> new Hit(o.getId(), o.getReference(), orderSubtitle(o))).toList();
        List<Hit> bookings = bookingRepository.findAll(bookingMatch(like), TOP_5).stream()
                .map(b -> new Hit(b.getId(), b.getCustomerName(), b.getService())).toList();
        return new Results(customers, orders, bookings);
    }

    private static String orderSubtitle(Order o) {
        String customer = o.getCustomerName() == null ? "" : o.getCustomerName();
        return o.getService() == null ? customer : (customer.isBlank() ? o.getService() : customer + " · " + o.getService());
    }

    private static Specification<Customer> customerMatch(String like) {
        return (root, q, cb) -> anyLike(cb, like,
                cb.lower(root.get("fullName")),
                cb.lower(cb.coalesce(root.get("email"), "")),
                cb.lower(cb.coalesce(root.get("phone"), "")));
    }

    private static Specification<Order> orderMatch(String like) {
        return (root, q, cb) -> anyLike(cb, like,
                cb.lower(cb.coalesce(root.get("reference"), "")),
                cb.lower(cb.coalesce(root.get("customerName"), "")),
                cb.lower(cb.coalesce(root.get("service"), "")));
    }

    private static Specification<Booking> bookingMatch(String like) {
        return (root, q, cb) -> anyLike(cb, like,
                cb.lower(cb.coalesce(root.get("customerName"), "")),
                cb.lower(cb.coalesce(root.get("service"), "")));
    }

    @SafeVarargs
    private static Predicate anyLike(jakarta.persistence.criteria.CriteriaBuilder cb, String like,
                                     jakarta.persistence.criteria.Expression<String>... fields) {
        Predicate[] likes = new Predicate[fields.length];
        for (int i = 0; i < fields.length; i++) {
            likes[i] = cb.like(fields[i], like);
        }
        return cb.or(likes);
    }

    /** A single search hit: the entity id, a primary label, and a subtitle. */
    public record Hit(UUID id, String label, String sublabel) {
    }

    /** Grouped search results across the searchable modules. */
    public record Results(List<Hit> customers, List<Hit> orders, List<Hit> bookings) {
    }
}
