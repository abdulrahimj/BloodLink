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
 * The REST half of notification delivery — what the bell icon reads on page load,
 * and how it is cleared.
 *
 * This exists alongside the WebSocket push rather than being replaced by it. A
 * socket only carries what arrives while it is open, so a donor opening the app
 * after two days offline would otherwise see an empty bell over a database full of
 * appeals addressed to them. The socket keeps a screen current; these endpoints are
 * what make it correct in the first place.
 *
 * Every route is scoped to the caller by their token. SecurityConfig requires only
 * a valid token on this path — deliberately, because all four roles receive
 * notifications — so "whose notifications" is decided here and in NotificationService,
 * never by a path or query parameter a client could change.
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
     * Just the badge number.
     *
     * A separate endpoint from the listing above because the two are wanted at
     * different moments: the count is needed on every page load, the list only when
     * someone actually opens the bell.
     */
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new UnreadCountResponse(notificationService.unreadCount(principal.getId()));
    }

    /**
     * "Mark all as read" — clears the badge.
     *
     * Declared before the single-notification route below because "read-all" is a
     * literal path segment and {notificationId} is a UUID: a client asking for
     * /read-all must reach this method rather than failing to parse as an id.
     */
    @PatchMapping("/read-all")
    public UnreadCountResponse readAll(@AuthenticationPrincipal AuthenticatedUser principal) {
        notificationService.markAllRead(principal.getId());
        // Returns the count after clearing (zero) so the caller can set the badge
        // from the response rather than assuming the write did what it asked.
        return new UnreadCountResponse(notificationService.unreadCount(principal.getId()));
    }

    /**
     * Marks one notification read. PATCH rather than PUT: this changes one flag on
     * the notification and never replaces its message, type or timestamp.
     *
     * A notification belonging to somebody else is a 404, not a 403 — the caller has
     * no business learning that the id exists.
     */
    @PatchMapping("/{notificationId}")
    public NotificationResponse markRead(
        @PathVariable UUID notificationId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return notificationService.markRead(notificationId, principal.getId());
    }
}
