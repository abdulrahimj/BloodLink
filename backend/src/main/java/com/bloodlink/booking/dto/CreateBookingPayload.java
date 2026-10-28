package com.bloodlink.booking.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/bookings: one bank, one date (today to today + 30, checked in DonationBookingService), and a half-day.
 */
public record CreateBookingPayload(

    @NotNull(message = "Choose a blood bank")
    UUID bloodBankId,

    @NotNull(message = "Choose a date")
    LocalDate bookingDate,

    @NotNull(message = "Choose morning or afternoon")
    @Pattern(regexp = "MORNING|AFTERNOON", message = "Choose morning or afternoon")
    String timeOfDay
) {}
