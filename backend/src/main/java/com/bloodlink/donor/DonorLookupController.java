package com.bloodlink.donor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.donor.dto.DonorLookupResponse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Donor lookup for "Record Donation": bank staff and ADMIN only, matched ahead of the general /api/donors/** rule.
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
        String phone
    ) {
        return donorLookupService.lookupByPhone(phone);
    }
}
