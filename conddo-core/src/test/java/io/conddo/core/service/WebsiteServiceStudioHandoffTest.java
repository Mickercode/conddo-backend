package io.conddo.core.service;

import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.WebsiteChangeRequest;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.WebsiteChangeRequestRepository;
import io.conddo.core.studio.StudioJobGateway;
import io.conddo.core.studio.StudioJobGateway.StudioJobRef;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import io.conddo.core.vertical.VerticalConfigRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The website change-request → Studio job hand-off (SERVICE_TOPOLOGY.md §4),
 * unit-tested with mocked collaborators: an unbuilt site requests a WEBSITE_BUILD,
 * a live one a WEBSITE_REVISION, the job id is linked back, and a Studio outage
 * (or no Studio configured) leaves the request PENDING without failing.
 */
class WebsiteServiceStudioHandoffTest {

    private final UUID tenantId = UUID.randomUUID();
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final WebsiteChangeRequestRepository changeRepo = mock(WebsiteChangeRequestRepository.class);
    private final TenantSession tenantSession = mock(TenantSession.class);
    private final StudioJobGateway gateway = mock(StudioJobGateway.class);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private WebsiteService service(Optional<StudioJobGateway> studio) {
        return new WebsiteService(tenantRepository, changeRepo, new VerticalConfigRegistry(),
                tenantSession, studio, "conddo.io");
    }

    @Test
    void unbuiltSiteRequestsAWebsiteBuildAndLinksTheJob() {
        Tenant tenant = new Tenant("Glam by Adaeze", "glam", "fashion", "starter"); // websiteStatus = NOT_STARTED
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(changeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID jobId = UUID.randomUUID();
        when(gateway.createJob(eq(tenantId), eq("WEBSITE_BUILD"), anyString(), anyMap()))
                .thenReturn(Optional.of(new StudioJobRef(jobId, "WB-7", "AVAILABLE")));
        TenantContext.set(tenantId);

        WebsiteService.ChangeRequestView view = service(Optional.of(gateway))
                .requestChange("hero", "Make my logo bigger");

        assertEquals("SUBMITTED", view.status());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> brief = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(gateway).createJob(eq(tenantId), eq("WEBSITE_BUILD"), title.capture(), brief.capture());
        assertEquals("Website build — Glam by Adaeze", title.getValue());
        assertEquals("Make my logo bigger", brief.getValue().get("details"));
        assertEquals("Glam by Adaeze", brief.getValue().get("businessName"));

        // Saved once on create, once after the job id is linked.
        ArgumentCaptor<WebsiteChangeRequest> saved = ArgumentCaptor.forClass(WebsiteChangeRequest.class);
        verify(changeRepo, times(2)).save(saved.capture());
        assertEquals(jobId, saved.getValue().getStudioJobId());
        assertEquals("SUBMITTED", saved.getValue().getStatus());
    }

    @Test
    void liveSiteRequestsAWebsiteRevision() {
        Tenant tenant = new Tenant("Glam", "glam", "fashion", "starter");
        tenant.setWebsiteStatus("LIVE");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(changeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.createJob(eq(tenantId), eq("WEBSITE_REVISION"), anyString(), anyMap()))
                .thenReturn(Optional.of(new StudioJobRef(UUID.randomUUID(), "WR-3", "AVAILABLE")));
        TenantContext.set(tenantId);

        service(Optional.of(gateway)).requestChange(null, "Swap the hero image");

        verify(gateway).createJob(eq(tenantId), eq("WEBSITE_REVISION"), anyString(), anyMap());
    }

    @Test
    void studioOutageLeavesTheRequestPending() {
        Tenant tenant = new Tenant("Glam", "glam", "fashion", "starter");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(changeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.createJob(any(), any(), any(), any())).thenReturn(Optional.empty());
        TenantContext.set(tenantId);

        WebsiteService.ChangeRequestView view = service(Optional.of(gateway))
                .requestChange("hero", "Tweak copy");

        assertEquals("PENDING", view.status());
        verify(changeRepo, times(1)).save(any());   // no re-save (no job to link)
    }

    @Test
    void noStudioConfiguredRecordsPendingAndSkipsHandOff() {
        Tenant tenant = new Tenant("Glam", "glam", "fashion", "starter");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(changeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.set(tenantId);

        WebsiteService.ChangeRequestView view = service(Optional.empty())
                .requestChange("hero", "Tweak copy");

        assertEquals("PENDING", view.status());
        verifyNoInteractions(gateway);
    }
}
