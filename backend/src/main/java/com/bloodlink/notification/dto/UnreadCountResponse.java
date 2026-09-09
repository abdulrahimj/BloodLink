package com.bloodlink.notification.dto;

/**
 * The bell's badge number.
 *
 * Wrapped in an object rather than returned as a bare number so the response is
 * valid, self-describing JSON that can gain a field later (a per-type breakdown,
 * say) without every existing client breaking on the shape change.
 */
public record UnreadCountResponse(long unreadCount) {}
