package com.bloodlink.donation;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.donation.dto.DonationRecordedResponse;
import com.bloodlink.donation.dto.RecordDonationPayload;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * "Record Donation" — the blood bank / lab staff use case that finally writes to
 * the donations table.
 *
 * SecurityConfig has restricted /api/donations/** to BLOOD_BANK_STAFF and ADMIN
 * since before anything served it; this is the route that rule was waiting for.
 * Which bank a staff member records against is narrowed again inside
 * DonationService via ActingBankResolver — their own, always.
 *
 * POST only. A donation is an event that happened, not a record to be edited:
 * there is no amend or delete route, because correcting one would have to
 * unwind the donor's eligibility date and the units already issuable from stock,
 * which is a reversal flow in its own right and not something to smuggle in
 * behind a PATCH.
 */
@RestController
@RequestMapping("/api/donations")
public class DonationController {

    private final DonationService donationService;

    DonationController(DonationService donationService) {
        this.donationService = donationService;
    }

    /**
     * Records a donation, updates the donor's eligibility clock, and adds the
     * units to stock — all or nothing.
     *
     * 201 with a receipt that includes the resulting stock figure, so the screen
     * can confirm the inventory half of the action actually landed rather than
     * leaving staff to go and check.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DonationRecordedResponse record(
        @Valid @RequestBody RecordDonationPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return donationService.recordDonation(payload, principal);
    }
}
