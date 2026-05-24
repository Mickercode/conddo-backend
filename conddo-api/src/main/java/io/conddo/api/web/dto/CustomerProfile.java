package io.conddo.api.web.dto;

import io.conddo.core.domain.Customer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer profile (§11.3 detail). {@code orders}/{@code avgOrderValue} are 0
 * until the Orders module (§11.4) is built and can aggregate them.
 */
public record CustomerProfile(
        UUID id,
        String name,
        String email,
        String phone,
        String tag,
        List<String> tags,
        OffsetDateTime memberSince,
        BigDecimal totalSpent,
        long orders,
        BigDecimal avgOrderValue,
        OffsetDateTime lastActive,
        String notes,
        Map<String, Object> measurements
) {
    public static CustomerProfile from(Customer c) {
        List<String> tags = c.getTags();
        String tag = (tags == null || tags.isEmpty()) ? null : tags.get(0);
        return new CustomerProfile(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(),
                tag, tags, c.getCreatedAt(), c.getTotalSpent(), 0L, BigDecimal.ZERO,
                c.getLastActive(), c.getNotes(), c.getMeasurements());
    }
}
