package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.PharmacyAiAssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Pharmacy AI Product Assistant (Spec v2 §12C). Two endpoints:
 * <ul>
 *   <li>{@code POST /ai/product-from-image} — pharmacist uploads a
 *       photo of the packaging, gets back a structured product card
 *       to pre-fill the create form.</li>
 *   <li>{@code POST /ai/description} — generate description + warnings
 *       from minimal seed data.</li>
 * </ul>
 *
 * <p>Both require a staff role (TENANT_ADMIN / STAFF / SUPER_ADMIN);
 * neither auto-saves anything — the pharmacist always reviews the
 * suggestion before creating the product (Spec v2 implementation note
 * 4).
 */
@RestController
@RequestMapping("/api/v1/pharmacy/ai")
public class PharmacyAiAssistantController {

    private static final String STAFF = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";

    private final PharmacyAiAssistantService service;

    public PharmacyAiAssistantController(PharmacyAiAssistantService service) {
        this.service = service;
    }

    @PostMapping("/product-from-image")
    @PreAuthorize(STAFF)
    public ApiResponse<Map<String, Object>> productFromImage(
            @Valid @RequestBody ProductFromImageRequest body) {
        return ApiResponse.ok(service.productFromImage(body.imageUrl()));
    }

    @PostMapping("/description")
    @PreAuthorize(STAFF)
    public ApiResponse<Map<String, Object>> description(
            @Valid @RequestBody DescriptionRequest body) {
        return ApiResponse.ok(service.description(
                body.nameGeneric(), body.nameBrand(), body.indications()));
    }

    public record ProductFromImageRequest(@NotBlank String imageUrl) {
    }

    public record DescriptionRequest(@NotBlank String nameGeneric,
                                     String nameBrand,
                                     String indications) {
    }
}
