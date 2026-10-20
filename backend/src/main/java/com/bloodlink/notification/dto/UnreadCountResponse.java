package com.bloodlink.notification.dto;

/**
 * The bell's badge number, wrapped in an object so the response can gain fields later.
 */
public record UnreadCountResponse(long unreadCount) {}
