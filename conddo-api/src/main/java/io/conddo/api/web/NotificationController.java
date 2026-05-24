package io.conddo.api.web;

import io.conddo.api.web.dto.NotificationFeedResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.NotificationFeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The topbar notifications bell (§11.12). Tenant-scoped (RLS); open to any staff
 * role. Producers in other modules enqueue notices via {@code NotificationFeedService}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class NotificationController {

    private final NotificationFeedService notificationFeedService;

    public NotificationController(NotificationFeedService notificationFeedService) {
        this.notificationFeedService = notificationFeedService;
    }

    @GetMapping
    public ApiResponse<NotificationFeedResponse> feed(@RequestParam(required = false, defaultValue = "false") boolean unread) {
        return ApiResponse.ok(NotificationFeedResponse.from(notificationFeedService.feed(unread)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        notificationFeedService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationFeedService.markAllRead();
        return ResponseEntity.noContent().build();
    }
}
