package com.bloodlink.donor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.donor.dto.DonorLookupResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Donor lookup for "Record Donation": active blood bank staff only (Business Rule 8), ahead of /api/donors/**.
 * Kept out of DonorController, whose routes are all scoped to the caller's own record.
 */
@RestController
@RequestMapping("/api/donors")
public class DonorLookupController {

    private final DonorLookupService donorLookupService;

    DonorLookupController(DonorLookupService donorLookupService) {
        this.donorLookupService = donorLookupService;
    }

    /**
     * GET /api/donors/lookup?phone=...: exact match, 404 when nobody matches; a blank phone gets its own 400.
     * No @Validated on this class; see AppealController.reach for why.
     */
    @GetMapping("/lookup")
    public DonorLookupResponse lookup(
        @RequestParam
        @NotBlank(message = "Phone number is required")
        @Size(max = 32, message = "Phone number must be 32 characters or fewer")
        String phone,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return donorLookupService.lookupByPhone(phone, principal);
    }
}
