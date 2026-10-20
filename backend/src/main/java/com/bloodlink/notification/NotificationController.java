package com.bloodlink.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.notification.dto.NotificationResponse;
import com.bloodlink.notification.dto.UnreadCountResponse;
import com.bloodlink.security.AuthenticatedUser;

/**
 * The REST half of notifications: what the bell reads on page load and how it is cleared,
 * so nothing missed while offline is lost. Every route is scoped to the caller's token.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** The caller's recent notifications, newest first — the bell's dropdown. */
    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return notificationService.listForUser(principal.getId());
    }

    /**
     * Just the badge number, separate because it is needed on every page load and the list only when the bell opens.
     */
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new UnreadCountResponse(notificationService.unreadCount(principal.getId()));
    }

    /**
     * "Mark all as read"; declared before the {notificationId} route so "read-all" is not parsed as an id.
     */
    @PatchMapping("/read-all")
    public UnreadCountResponse readAll(@AuthenticationPrincipal AuthenticatedUser principal) {
        notificationService.markAllRead(principal.getId());
        // Returns the count after clearing (zero) so the caller can set the badge
        // from the response rather than assuming the write did what it asked.
        return new UnreadCountResponse(notificationService.unreadCount(principal.getId()));
    }

    /**
     * Marks one notification read; someone else's notification is a 404, not a 403.
     */
    @PatchMapping("/{notificationId}")
    public NotificationResponse markRead(
        @PathVariable UUID notificationId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return notificationService.markRead(notificationId, principal.getId());
    }
}
