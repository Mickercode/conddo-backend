package io.conddo.api.pharmacy;

import io.conddo.core.domain.PharmacyDiscount;
import io.conddo.core.domain.User;
import io.conddo.core.events.DiscountPendingApprovalEvent;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.service.NotificationFeedService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Pharmacy Spec v2 §12B — bell-feed nudge to the TENANT_ADMIN when a
 * staff member submits a discount that needs approval. Runs after the
 * discount row's commit so a rolled-back create never notifies, and
 * on a fresh thread so a flaky feed write never bubbles into the
 * POST response.
 */
@Component
public class DiscountPendingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DiscountPendingNotificationListener.class);

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final NotificationFeedService notificationFeedService;
    private final TenantSession tenantSession;

    public DiscountPendingNotificationListener(ProductRepository productRepository,
                                               UserRepository userRepository,
                                               NotificationFeedService notificationFeedService,
                                               TenantSession tenantSession) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.notificationFeedService = notificationFeedService;
        this.tenantSession = tenantSession;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDiscountPending(DiscountPendingApprovalEvent event) {
        try {
            TenantContext.set(event.tenantId());
            tenantSession.bind();
            Optional<User> owner = userRepository.findFirstByRoleOrderByCreatedAtAsc("TENANT_ADMIN");

            String productLabel = productRepository.findById(event.productId())
                    .map(p -> p.getNameGeneric() == null ? p.getName() : p.getNameGeneric())
                    .orElse("a product");
            String title = "Discount pending approval";
            String body = "A " + describeDiscount(event)
                    + " on " + productLabel + " is awaiting your approval.";
            notificationFeedService.create("DISCOUNT_PENDING", title, body,
                    owner.map(User::getId).orElse(null));
        } catch (RuntimeException ex) {
            log.warn("Discount-pending notification failed for tenant {} discount {}: {}",
                    event.tenantId(), event.discountId(), ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private static String describeDiscount(DiscountPendingApprovalEvent event) {
        String value = event.discountValue();
        boolean percentage = PharmacyDiscount.TYPE_PERCENTAGE.equals(event.discountType());
        String formatted = percentage ? value + "%" : "₦" + value;
        String label = event.discountLabel();
        if (label != null && !label.isBlank()) {
            return label + " (" + formatted + " off)";
        }
        return formatted + " discount";
    }
}
