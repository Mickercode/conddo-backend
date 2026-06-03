package io.conddo.studio.sse;

import io.conddo.studio.auth.StudioPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Studio job-board SSE stream (Infrastructure §13.4). The authenticated staff
 * member opens a long-lived connection here and receives {@code job.created},
 * {@code job.claimed}, {@code job.submitted}, {@code notification.created}, etc.
 * — filtered by their role/skills (see {@link SseService}).
 *
 * <p>Browsers' built-in {@code EventSource} can't set an {@code Authorization}
 * header, so the frontend uses an EventSource polyfill (or a Cookie) to pass the
 * STUDIO_JWT. The endpoint itself is just an authenticated SSE producer.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobEventsController {

    private final SseService sseService;

    public JobEventsController(SseService sseService) {
        this.sseService = sseService;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        UUID staffId = StudioPrincipal.staffId(jwt);
        return sseService.subscribe(staffId);
    }
}
