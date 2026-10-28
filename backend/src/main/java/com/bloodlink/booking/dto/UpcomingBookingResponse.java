package com.bloodlink.booking.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.DonationBooking;
import com.bloodlink.domain.enums.BookingTimeOfDay;

/**
 * One upcoming visit on the Blood Bank Dashboard (Business Rule 29): deliberately narrow, no phone or address.
 */
public record UpcomingBookingResponse(
    UUID id,
    LocalDate bookingDate,
    BookingTimeOfDay timeOfDay,
    String donorName,
    /** The donor's profile group, or null when none is recorded yet (Business Rule 28). */
    String bloodType
) {

    /** Must run inside the loading transaction (donor and account are fetch-joined by the query). */
    public static UpcomingBookingResponse from(DonationBooking booking) {
        return new UpcomingBookingResponse(
            booking.getId(),
            booking.getBookingDate(),
            booking.getTimeOfDay(),
            booking.getDonor().getUser().getFullName(),
            booking.getDonor().getBloodType() == null ? null : booking.getDonor().getBloodType().getLabel()
        );
    }
}
