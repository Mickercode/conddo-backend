package io.conddo.core.studio;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for handing a unit of creative work to <b>Conddo Studio</b> — the
 * standalone jobs/production service (website builds, ad creatives, graphics,
 * copy). The platform (Control plane) creates a job here; Studio (Production
 * plane) fulfils it. See {@code SERVICE_TOPOLOGY.md} §4.
 *
 * <p>The two services share no schema, so the link is carried by value:
 * {@code tenantId} on the way in, {@link StudioJobRef#jobId()} on the way back
 * (stored alongside the originating record). Implemented in {@code conddo-api}
 * as a signed HTTP call; absent (so callers skip the hand-off) when Studio isn't
 * configured. Implementations must never throw on a transport failure — they
 * return {@link Optional#empty()} so a Studio outage can't fail the owner's request.
 */
public interface StudioJobGateway {

    /**
     * Creates a Studio job, or {@link Optional#empty()} if it could not be placed.
     *
     * @param tenantId  the originating tenant (soft reference in Studio)
     * @param jobType   a Studio job-type id, e.g. {@code WEBSITE_BUILD}, {@code WEBSITE_REVISION}
     * @param title     human-readable job title for the board
     * @param brief     structured context (source record id, business name, the request details)
     */
    Optional<StudioJobRef> createJob(UUID tenantId, String jobType, String title, Map<String, Object> brief);

    /** A handle to a created Studio job, reconciled against the originating record. */
    record StudioJobRef(UUID jobId, String jobNumber, String status) {
    }
}
