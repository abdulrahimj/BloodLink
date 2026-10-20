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
 * "Record Donation" for blood bank staff, who always record against their own bank (via DonationService).
 * POST only: a donation is an event, with no amend or delete route.
 */
@RestController
@RequestMapping("/api/donations")
public class DonationController {

    private final DonationService donationService;

    DonationController(DonationService donationService) {
        this.donationService = donationService;
    }

    /**
     * Records a donation, updates the donor's eligibility and adds the units to stock, all or nothing;
     * 409 for a donor in recovery. 201 with a receipt including the resulting stock figure.
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
