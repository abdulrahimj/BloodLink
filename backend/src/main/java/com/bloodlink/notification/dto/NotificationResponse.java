package com.bloodlink.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.Notification;
import com.bloodlink.domain.enums.NotificationType;

/**
 * One notification in the single shape both GET /api/notifications and the WebSocket push use,
 * so live and page-load notifications render the same.
 */
public record NotificationResponse(
    UUID id,
    String message,
    NotificationType type,
    boolean read,
    boolean accepted,
    Instant createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getMessage(),
            notification.getType(),
            notification.isRead(),
            notification.isAccepted(),
            notification.getCreatedAt()
        );
    }
}
