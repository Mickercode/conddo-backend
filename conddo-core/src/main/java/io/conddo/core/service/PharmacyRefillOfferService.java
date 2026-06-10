package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PharmacyRefillOffer;
import io.conddo.core.domain.PharmacyRefillOfferClaim;
import io.conddo.core.domain.Product;
import io.conddo.core.repository.PharmacyRefillOfferClaimRepository;
import io.conddo.core.repository.PharmacyRefillOfferRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Refill offer lifecycle (Pharmacy Spec v2 §12E).
 *
 * <p>Flow: pharmacist defines an offer per product → after dispensing
 * an order, they {@link #issue} it to that customer (creates a claim
 * with a unique short code) → customer presents the code at public
 * checkout → {@link #validate} confirms it → {@link #redeem} flips
 * the claim to used with the resulting order id.
 *
 * <p>Codes are generated server-side from a 32-symbol alphabet that
 * deliberately excludes ambiguous glyphs ({@code O 0 I 1 L}) so the
 * code is mis-typed less often when read aloud at the counter.
 */
@Service
public class PharmacyRefillOfferService {

    /** Short, unambiguous alphabet — no O/0/I/1/L to reduce typo rate. */
    private static final char[] CODE_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_BODY_LENGTH = 4;

    private final PharmacyRefillOfferRepository offerRepository;
    private final PharmacyRefillOfferClaimRepository claimRepository;
    private final ProductRepository productRepository;
    private final PharmacyReminderService reminderService;
    private final TenantSession tenantSession;
    private final Clock clock;
    private final SecureRandom rng = new SecureRandom();

    public PharmacyRefillOfferService(PharmacyRefillOfferRepository offerRepository,
                                      PharmacyRefillOfferClaimRepository claimRepository,
                                      ProductRepository productRepository,
                                      PharmacyReminderService reminderService,
                                      TenantSession tenantSession,
                                      Clock clock) {
        this.offerRepository = offerRepository;
        this.claimRepository = claimRepository;
        this.productRepository = productRepository;
        this.reminderService = reminderService;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    @Transactional
    public PharmacyRefillOffer create(UUID productId, String discountType, BigDecimal discountValue,
                                      int validDays, int maxUses, String message, UUID createdBy) {
        tenantSession.bind();
        if (productId == null) {
            throw new IllegalArgumentException("productId is required");
        }
        if (!PharmacyRefillOffer.TYPE_PERCENTAGE.equals(discountType)
                && !PharmacyRefillOffer.TYPE_FIXED.equals(discountType)) {
            throw new IllegalArgumentException("discountType must be PERCENTAGE or FIXED");
        }
        if (discountValue == null || discountValue.signum() <= 0) {
            throw new IllegalArgumentException("discountValue must be > 0");
        }
        if (validDays <= 0) {
            throw new IllegalArgumentException("validDays must be > 0");
        }
        if (maxUses <= 0) {
            throw new IllegalArgumentException("maxUses must be > 0");
        }
        return offerRepository.save(new PharmacyRefillOffer(TenantContext.require(), productId,
                discountType, discountValue, validDays, maxUses, message, createdBy));
    }

    @Transactional(readOnly = true)
    public List<PharmacyRefillOffer> list(boolean activeOnly) {
        tenantSession.bind();
        return activeOnly ? offerRepository.findByActiveTrueOrderByCreatedAtDesc()
                : offerRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public PharmacyRefillOffer get(UUID id) {
        tenantSession.bind();
        return offerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Refill offer not found"));
    }

    @Transactional
    public IssuedClaim issue(UUID offerId, UUID customerId, boolean sendSms) {
        tenantSession.bind();
        PharmacyRefillOffer offer = get(offerId);
        if (!offer.isActive()) {
            throw new IllegalArgumentException("Refill offer is inactive");
        }
        OffsetDateTime issuedAt = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = issuedAt.plus(offer.getValidDays(), ChronoUnit.DAYS);
        String code = generateUniqueCode();
        PharmacyRefillOfferClaim claim = claimRepository.save(new PharmacyRefillOfferClaim(
                TenantContext.require(), offerId, customerId, code, issuedAt, expiresAt));
        // §12D — when the pharmacist asks for the SMS to be dispatched,
        // pre-interpolate the offer-specific tokens ({offerCode}/
        // {validDays}) and queue a one-shot SCHEDULED reminder. The
        // hourly reminder scheduler picks it up, interpolates the
        // remaining customer/product/store tokens, and dispatches via
        // Brevo. Pharmacist-skipped or message-less offers are no-ops.
        if (sendSms && offer.getMessage() != null && !offer.getMessage().isBlank()) {
            String prefilled = offer.getMessage()
                    .replace("{offerCode}", code)
                    .replace("{validDays}", String.valueOf(offer.getValidDays()));
            reminderService.create(customerId, offer.getProductId(),
                    "REFILL_OFFER", prefilled, issuedAt, null, null, offer.getCreatedBy());
        }
        return new IssuedClaim(claim, offer, sendSms);
    }

    /**
     * Public-side validator. Looks up the claim by code, confirms it's
     * unused and unexpired, and returns enough detail for the FE to
     * decide whether to surface the offer at checkout.
     */
    @Transactional(readOnly = true)
    public ValidationResult validate(String offerCode) {
        tenantSession.bind();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return claimRepository.findByOfferCode(offerCode)
                .map(claim -> {
                    if (claim.isUsed()) {
                        return ValidationResult.invalid("USED");
                    }
                    if (claim.isExpiredAt(now)) {
                        return ValidationResult.invalid("EXPIRED");
                    }
                    PharmacyRefillOffer offer = offerRepository.findById(claim.getOfferId())
                            .orElse(null);
                    if (offer == null || !offer.isActive()) {
                        return ValidationResult.invalid("INACTIVE");
                    }
                    Product product = productRepository.findById(offer.getProductId()).orElse(null);
                    return ValidationResult.valid(claim, offer, product);
                })
                .orElse(ValidationResult.invalid("NOT_FOUND"));
    }

    /**
     * Mark a claim as used. Called from the checkout flow once an
     * order has been created. Idempotency: throws if the claim is
     * already used so the caller can decide whether to refuse the
     * order or just ignore the offer.
     */
    @Transactional
    public PharmacyRefillOfferClaim redeem(String offerCode, UUID customerId, UUID orderId) {
        tenantSession.bind();
        PharmacyRefillOfferClaim claim = claimRepository.findByOfferCode(offerCode)
                .orElseThrow(() -> new NotFoundException("Refill offer code not found"));
        if (!customerId.equals(claim.getCustomerId())) {
            throw new IllegalArgumentException(
                    "Refill offer was issued to a different customer");
        }
        if (claim.isUsed()) {
            throw new IllegalArgumentException("Refill offer code has already been used");
        }
        if (claim.isExpiredAt(OffsetDateTime.now(clock))) {
            throw new IllegalArgumentException("Refill offer code has expired");
        }
        claim.markUsed(OffsetDateTime.now(clock), orderId);
        return claimRepository.save(claim);
    }

    /** Generate a non-colliding REFILL-XXXX code; retry up to 5 times. */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            char[] body = new char[CODE_BODY_LENGTH];
            for (int i = 0; i < CODE_BODY_LENGTH; i++) {
                body[i] = CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)];
            }
            String candidate = "REFILL-" + new String(body);
            if (claimRepository.findByOfferCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique refill code in 5 attempts");
    }

    public record IssuedClaim(PharmacyRefillOfferClaim claim, PharmacyRefillOffer offer,
                              boolean smsRequested) {
    }

    public record ValidationResult(boolean valid, String reason,
                                   PharmacyRefillOfferClaim claim,
                                   PharmacyRefillOffer offer,
                                   Product product) {
        public static ValidationResult valid(PharmacyRefillOfferClaim claim,
                                              PharmacyRefillOffer offer, Product product) {
            return new ValidationResult(true, null, claim, offer, product);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason, null, null, null);
        }
    }
}
