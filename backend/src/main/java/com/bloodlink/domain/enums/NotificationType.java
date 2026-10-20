package com.bloodlink.domain.enums;

public enum NotificationType {
    LOW_STOCK_ALERT,
    DONATION_APPEAL,
    NEW_REQUEST,
    REQUEST_STATUS_CHANGE,
    /**
     * A request's outstanding units changed after a contribution, sent to the other banks still able to act
     * (Business Rule 20, V15) so two banks don't both cover the same gap. The hospital gets REQUEST_STATUS_CHANGE.
     */
    REQUEST_PROGRESS
}
