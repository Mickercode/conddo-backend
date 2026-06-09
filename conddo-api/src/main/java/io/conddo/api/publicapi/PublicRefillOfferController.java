package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.domain.PharmacyRefillOffer;
import io.conddo.core.domain.PharmacyRefillOfferClaim;
import io.conddo.core.domain.Product;
import io.conddo.core.service.PharmacyRefillOfferService;
import io.conddo.core.service.PharmacyRefillOfferService.ValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public-side refill offer validator (Pharmacy Spec v2 §12E). The
 * customer's storefront fetches this when they paste a code at
 * checkout to confirm whether it's redeemable and to render the
 * discount preview. Site-key auth (site interceptor) +
 * customer-JWT auth (inline check) — the offer must have been issued
 * to the calling customer.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/pharmacy/refill-offer")
public class PublicRefillOfferController {

    private final PharmacyRefillOfferService service;
    private final CustomerJwtService customerJwtService;

    public PublicRefillOfferController(PharmacyRefillOfferService service,
                                       CustomerJwtService customerJwtService) {
        this.service = service;
        this.customerJwtService = customerJwtService;
    }

    @GetMapping("/{offerCode}")
    public Map<String, Object> validate(@PathVariable String offerCode,
                                        HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        ValidationResult result = service.validate(offerCode);
        if (!result.valid()) {
            return Map.of("valid", false, "reason", result.reason());
        }
        PharmacyRefillOfferClaim claim = result.claim();
        if (!customerId.equals(claim.getCustomerId())) {
            // The code is real and live, but was issued to someone else.
            return Map.of("valid", false, "reason", "WRONG_CUSTOMER");
        }
        PharmacyRefillOffer offer = result.offer();
        Product product = result.product();
        Map<String, Object> offerView = new LinkedHashMap<>();
        offerView.put("productId", offer.getProductId());
        offerView.put("discountType", offer.getDiscountType());
        offerView.put("discountValue", offer.getDiscountValue());
        if (product != null && product.getPrice() != null) {
            BigDecimal discounted = offer.applyTo(product.getPrice());
            offerView.put("price", product.getPrice());
            offerView.put("discountedPrice", discounted);
        }
        offerView.put("expiresAt", claim.getExpiresAt());
        return Map.of("valid", true, "offer", offerView);
    }
}
