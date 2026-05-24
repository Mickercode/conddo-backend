package io.conddo.api.web;

import io.conddo.api.web.dto.CampaignDto;
import io.conddo.api.web.dto.ConnectRequest;
import io.conddo.api.web.dto.ConnectionDto;
import io.conddo.api.web.dto.CreateCampaignRequest;
import io.conddo.api.web.dto.CreateLeadRequest;
import io.conddo.api.web.dto.CreatePostRequest;
import io.conddo.api.web.dto.LeadDto;
import io.conddo.api.web.dto.MarketingPostDto;
import io.conddo.api.web.dto.UpdateLeadRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.MarketingService;
import io.conddo.core.service.MarketingService.Funnel;
import io.conddo.core.service.MarketingService.Summary;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Marketing (§11.8): social posts/calendar and email/SMS campaigns. Tenant from
 * the JWT (RLS). Reads open to any staff role; writes default to TENANT_ADMIN /
 * SUPER_ADMIN. Leads, connections, and the overview summary are added alongside.
 */
@RestController
@RequestMapping("/api/v1/marketing")
public class MarketingController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final MarketingService marketingService;

    public MarketingController(MarketingService marketingService) {
        this.marketingService = marketingService;
    }

    // ----- posts --------------------------------------------------------------

    @GetMapping("/posts")
    @PreAuthorize(READ)
    public ApiResponse<List<MarketingPostDto>> posts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String platform) {
        return ApiResponse.ok(marketingService.listPosts(from, to, platform).stream()
                .map(MarketingPostDto::from).toList());
    }

    @PostMapping("/posts")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<MarketingPostDto>> createPost(@Valid @RequestBody CreatePostRequest request) {
        MarketingPostDto body = MarketingPostDto.from(marketingService.createPost(
                request.title(), request.content(), request.platforms(), request.mediaIds(), request.scheduledAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping("/posts/{id}")
    @PreAuthorize(READ)
    public ApiResponse<MarketingPostDto> getPost(@PathVariable UUID id) {
        return ApiResponse.ok(MarketingPostDto.from(marketingService.getPost(id)));
    }

    @PatchMapping("/posts/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<MarketingPostDto> updatePost(@PathVariable UUID id, @RequestBody CreatePostRequest request) {
        return ApiResponse.ok(MarketingPostDto.from(marketingService.updatePost(
                id, request.title(), request.content(), request.platforms(), request.mediaIds(), request.scheduledAt())));
    }

    @DeleteMapping("/posts/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> deletePost(@PathVariable UUID id) {
        marketingService.deletePost(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{id}/publish")
    @PreAuthorize(WRITE)
    public ApiResponse<MarketingPostDto> publishPost(@PathVariable UUID id) {
        return ApiResponse.ok(MarketingPostDto.from(marketingService.publishPost(id)));
    }

    // ----- campaigns ----------------------------------------------------------

    @GetMapping("/campaigns")
    @PreAuthorize(READ)
    public ApiResponse<List<CampaignDto>> campaigns(@RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String status) {
        return ApiResponse.ok(marketingService.listCampaigns(type, status).stream()
                .map(CampaignDto::from).toList());
    }

    @PostMapping("/campaigns")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<CampaignDto>> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        CampaignDto body = CampaignDto.from(marketingService.createCampaign(
                request.name(), request.type(), request.content(), request.audienceSize(), request.scheduledAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping("/campaigns/{id}")
    @PreAuthorize(READ)
    public ApiResponse<CampaignDto> getCampaign(@PathVariable UUID id) {
        return ApiResponse.ok(CampaignDto.from(marketingService.getCampaign(id)));
    }

    // ----- overview summary ---------------------------------------------------

    @GetMapping("/summary")
    @PreAuthorize(READ)
    public ApiResponse<Summary> summary() {
        return ApiResponse.ok(marketingService.summary());
    }

    // ----- leads --------------------------------------------------------------

    @GetMapping("/leads/funnel")
    @PreAuthorize(READ)
    public ApiResponse<Funnel> funnel() {
        return ApiResponse.ok(marketingService.funnel());
    }

    @GetMapping("/leads")
    @PreAuthorize(READ)
    public ApiResponse<List<LeadDto>> leads(@RequestParam(required = false) String stage) {
        return ApiResponse.ok(marketingService.listLeads(stage).stream().map(LeadDto::from).toList());
    }

    @PostMapping("/leads")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<LeadDto>> createLead(@Valid @RequestBody CreateLeadRequest request) {
        LeadDto body = LeadDto.from(marketingService.createLead(
                request.name(), request.email(), request.phone(), request.source()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PatchMapping("/leads/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<LeadDto> updateLead(@PathVariable UUID id, @RequestBody UpdateLeadRequest request) {
        return ApiResponse.ok(LeadDto.from(marketingService.updateLead(
                id, request.stage(), request.name(), request.value(), request.notes())));
    }

    // ----- connections --------------------------------------------------------

    @GetMapping("/connections")
    @PreAuthorize(READ)
    public ApiResponse<List<ConnectionDto>> connections() {
        return ApiResponse.ok(marketingService.connections().stream().map(ConnectionDto::from).toList());
    }

    @PostMapping("/connections")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<ConnectionDto>> connect(@Valid @RequestBody ConnectRequest request) {
        ConnectionDto body = ConnectionDto.from(marketingService.connect(request.platform(), request.handle()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @DeleteMapping("/connections/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> disconnect(@PathVariable UUID id) {
        marketingService.disconnect(id);
        return ResponseEntity.noContent().build();
    }
}
