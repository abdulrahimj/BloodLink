package com.bloodlink.domain.enums;

/**
 * The half of the day a donation visit is booked for (Business Rule 29); there are no exact times.
 */
public enum BookingTimeOfDay {
    MORNING,
    AFTERNOON;

    /** "morning" / "afternoon", as the notification sentences use it. */
    public String inWords() {
        return name().toLowerCase();
    }
}
