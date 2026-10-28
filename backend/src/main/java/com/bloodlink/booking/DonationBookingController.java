package com.bloodlink.booking;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.booking.dto.BookingResponse;
import com.bloodlink.booking.dto.CreateBookingPayload;
import com.bloodlink.booking.dto.UpcomingBookingResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Donation visits (Business Rule 29): a donor books, reads and cancels their own; bank staff list their bank's
 * upcoming ones. SecurityConfig and DonationBookingService both enforce who may call what.
 */
@RestController
@RequestMapping("/api/bookings")
public class DonationBookingController {

    private final DonationBookingService bookingService;

    DonationBookingController(DonationBookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** Books a visit for the calling donor; 201 with the booking. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse book(
        @Valid @RequestBody CreateBookingPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return bookingService.book(payload, principal);
    }

    /** The calling donor's open booking, or 204 when there is none. */
    @GetMapping("/me")
    public ResponseEntity<BookingResponse> mine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bookingService.currentForMe(principal)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Cancels the calling donor's open booking while its date is today or later. */
    @PostMapping("/me/cancel")
    public BookingResponse cancelMine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bookingService.cancelMine(principal);
    }

    /** The caller's bank's upcoming booked visits, for its own active staff. */
    @GetMapping("/upcoming")
    public List<UpcomingBookingResponse> upcoming(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bookingService.upcomingForMyBank(principal);
    }
}
