package io.conddo.studio.web;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.notifications.StudioNotificationService;
import io.conddo.studio.web.dto.NotificationFeedResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Staff notifications (Infrastructure §13.2). */
@RestController
@RequestMapping("/api/jobs/notifications")
public class NotificationController {

    private final StudioNotificationService notifications;

    public NotificationController(StudioNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<NotificationFeedResponse> feed(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(required = false, defaultValue = "false") boolean unread) {
        UUID staffId = StudioPrincipal.staffId(jwt);
        return ApiResponse.ok(NotificationFeedResponse.of(
                notifications.list(staffId, unread), notifications.unreadCount(staffId)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        notifications.markRead(id, StudioPrincipal.staffId(jwt));
        return ResponseEntity.noContent().build();
    }
}
