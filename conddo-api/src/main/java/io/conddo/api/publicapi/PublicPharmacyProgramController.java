package io.conddo.api.publicapi;

import io.conddo.api.web.PharmacyProgramController;
import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.service.PharmacyProgramService;
import io.conddo.core.service.PharmacyProgramService.EnrollResult;
import io.conddo.core.service.PharmacyProgramService.ProgramView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public-side drug programs surface (Pharmacy Roadmap Beta 3 + FE
 * handoff §3). Site-key auth via the PublicSiteInterceptor; the
 * enrol endpoint additionally requires a customer JWT and uses that
 * customer's id (no body — the customer can't enrol someone else).
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/pharmacy/programs")
public class PublicPharmacyProgramController {

    private final PharmacyProgramService service;
    private final CustomerJwtService customerJwtService;

    public PublicPharmacyProgramController(PharmacyProgramService service,
                                            CustomerJwtService customerJwtService) {
        this.service = service;
        this.customerJwtService = customerJwtService;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<ProgramView> published = service.listPublished();
        return Map.of("programs", published.stream()
                .map(PharmacyProgramController::toRow).toList());
    }

    @PostMapping("/{id}/enroll")
    public ResponseEntity<Map<String, Object>> enroll(@PathVariable UUID id,
                                                      HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        EnrollResult result = service.enroll(id, customerId, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enrollment", PharmacyProgramController.toEnrollmentRow(result.enrollment()));
        row.put("authorizationUrl", result.authorizationUrl());
        row.put("reference", result.reference());
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }
}
