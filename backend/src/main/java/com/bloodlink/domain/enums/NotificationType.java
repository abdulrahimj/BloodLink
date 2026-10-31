package com.bloodlink.domain.enums;

public enum NotificationType {
    LOW_STOCK_ALERT,
    DONATION_APPEAL,
    DONATION_APPEAL_ACCEPTED,
    NEW_REQUEST,
    REQUEST_STATUS_CHANGE,
    /**
     * A request's outstanding units changed after a contribution, sent to the other banks still able to act
     * (Business Rule 20, V15) so two banks don't both cover the same gap. The hospital gets REQUEST_STATUS_CHANGE.
     */
    REQUEST_PROGRESS,
    /** Batches of one bank 7 days from expiry, sent once per batch (Business Rule 26, V23). Informational only. */
    BATCH_EXPIRY_REMINDER,
    /** Expired batches of one bank still holding units, daily until written off (Business Rule 26, V23). */
    BATCH_EXPIRED,
    /** A donor booked a donation visit, sent to that bank's active staff (Business Rule 29, V27). */
    DONATION_BOOKED,
    /** A booking was cancelled, by the donor or automatically on a donation elsewhere (Business Rule 29, V27). */
    DONATION_BOOKING_CANCELLED,
    /** The day-before reminder to the donor, once per booking (Business Rule 29, V27). */
    DONATION_BOOKING_REMINDER,
    /** Tells an account holder an administrator reset their password (Business Rule 31, V28). Active accounts only. */
    PASSWORD_RESET_BY_ADMIN
}
