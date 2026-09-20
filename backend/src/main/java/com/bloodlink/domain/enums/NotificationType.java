package com.bloodlink.domain.enums;

public enum NotificationType {
    LOW_STOCK_ALERT,
    DONATION_APPEAL,
    NEW_REQUEST,
    REQUEST_STATUS_CHANGE,
    /**
     * The units still outstanding on a request changed, because some bank
     * contributed to it — sent to the other banks that can still act on it
     * (Business Rule 20, V15). Without it, two banks both looking at "4 units
     * needed" can each cover the whole amount and one of them issues units nobody
     * needed. Distinct from REQUEST_STATUS_CHANGE, which goes to the hospital that
     * raised the request rather than to the banks working on it.
     */
    REQUEST_PROGRESS
}
