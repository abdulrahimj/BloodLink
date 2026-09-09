package com.bloodlink.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.Notification;
import com.bloodlink.domain.enums.NotificationType;

/**
 * One notification, in the single shape every delivery route uses.
 *
 * The same record is returned by GET /api/notifications and pushed down the
 * WebSocket, so a client renders a live arrival and a page-load fetch with exactly
 * the same code. If the two ever diverged, a notification would look different
 * depending on whether the socket happened to be connected when it was created,
 * which is precisely the inconsistency real-time delivery is supposed to remove.
 */
public record NotificationResponse(
    UUID id,
    String message,
    NotificationType type,
    boolean read,
    Instant createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getMessage(),
            notification.getType(),
            notification.isRead(),
            notification.getCreatedAt()
        );
    }
}
