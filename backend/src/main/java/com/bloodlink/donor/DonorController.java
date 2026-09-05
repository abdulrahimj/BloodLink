package com.bloodlink.donor;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.donor.dto.DonationHistoryItem;
import com.bloodlink.donor.dto.DonorProfileResponse;
import com.bloodlink.security.AuthenticatedUser;

/**
 * The donor's own profile and history — nothing here takes an id parameter, because
 * every route is scoped to the caller by their token (SecurityConfig restricts
 * /api/donors/** to DONOR and ADMIN, but "me" always means the caller, never a
 * donor an ADMIN might otherwise try to look up by id).
 */
@RestController
@RequestMapping("/api/donors")
public class DonorController {

    private final DonorService donorService;

    DonorController(DonorService donorService) {
        this.donorService = donorService;
    }

    /** Dashboard summary: profile, eligibility, and donation totals. */
    @GetMapping("/me")
    public DonorProfileResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return donorService.getMyProfile(principal.getId());
    }

    /** Full donation history, most recent first. */
    @GetMapping("/me/donations")
    public List<DonationHistoryItem> myDonations(@AuthenticationPrincipal AuthenticatedUser principal) {
        return donorService.getMyDonationHistory(principal.getId());
    }
}
