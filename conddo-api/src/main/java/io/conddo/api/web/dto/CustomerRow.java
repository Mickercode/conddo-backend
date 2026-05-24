package io.conddo.api.web.dto;

import io.conddo.core.common.Initials;
import io.conddo.core.domain.Customer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A customer list row (§11.3). {@code orders} is 0 until the Orders module
 * (§11.4) lands; {@code tag} is the customer's primary (first) tag.
 */
public record CustomerRow(
        UUID id,
        String name,
        String initials,
        String phone,
        String email,
        BigDecimal totalSpent,
        long orders,
        OffsetDateTime lastActive,
        String tag
) {
    public static CustomerRow from(Customer c) {
        List<String> tags = c.getTags();
        String tag = (tags == null || tags.isEmpty()) ? null : tags.get(0);
        return new CustomerRow(c.getId(), c.getFullName(), Initials.of(c.getFullName()),
                c.getPhone(), c.getEmail(), c.getTotalSpent(), 0L, c.getLastActive(), tag);
    }
}
