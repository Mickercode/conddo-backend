package io.conddo.core.events;

import java.util.UUID;

/**
 * Published when a staff member creates a pharmacy discount (Pharmacy
 * Spec v2 §12B). The OrderNotificationListener-style fan-out on this
 * event surfaces a bell-feed nudge to the TENANT_ADMIN so they can
 * approve or reject the discount without polling.
 */
public record DiscountPendingApprovalEvent(UUID tenantId,
                                            UUID discountId,
                                            UUID productId,
                                            String discountLabel,
                                            String discountType,
                                            String discountValue,
                                            UUID createdBy) {
}
