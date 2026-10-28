package com.bloodlink.booking.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.DonationBooking;
import com.bloodlink.domain.enums.BookingStatus;
import com.bloodlink.domain.enums.BookingTimeOfDay;

/**
 * A donor's own booking (Business Rule 29), as the Donor Portal's booking card shows it.
 */
public record BookingResponse(
    UUID id,
    UUID bloodBankId,
    String bloodBankName,
    LocalDate bookingDate,
    BookingTimeOfDay timeOfDay,
    BookingStatus status,
    Instant createdAt
) {

    /** Must run inside the loading transaction, since the bank is lazy. */
    public static BookingResponse from(DonationBooking booking) {
        return new BookingResponse(
            booking.getId(),
            booking.getBloodBank().getId(),
            booking.getBloodBank().getName(),
            booking.getBookingDate(),
            booking.getTimeOfDay(),
            booking.getStatus(),
            booking.getCreatedAt()
        );
    }
}
