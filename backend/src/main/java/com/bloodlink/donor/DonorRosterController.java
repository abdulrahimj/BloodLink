package com.bloodlink.donor;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.donor.dto.DonationAtBankItem;
import com.bloodlink.donor.dto.DonorRosterPageResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The Donors page's data (Business Rule 21): who has given at this bank, and their donations here.
 * bloodBankId matters only for an ADMIN; staff use their own bank. No @Validated (see AppealController.reach).
 */
@RestController
@RequestMapping("/api/donors/roster")
public class DonorRosterController {

    private final DonorRosterService donorRosterService;

    DonorRosterController(DonorRosterService donorRosterService) {
        this.donorRosterService = donorRosterService;
    }

    /**
     * One page of this bank's donor roster, newest donation first by default; page/size as the Audit Log (400 if bad).
     * bloodType is a URL-encoded clinical label; eligibility and sort each take one of two fixed values.
     */
    @GetMapping
    public DonorRosterPageResponse list(
        @RequestParam(defaultValue = "0")
        @Min(value = 0, message = "page cannot be negative")
        int page,
        @RequestParam(defaultValue = "20")
        @Min(value = 1, message = "size must be at least 1")
        @Max(value = 100, message = "size cannot exceed 100")
        int size,
        @RequestParam(required = false)
        @Size(max = 64, message = "Search text must be 64 characters or fewer")
        String q,
        @RequestParam(required = false)
        @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
        String bloodType,
        @RequestParam(required = false)
        @Pattern(regexp = "ELIGIBLE|IN_RECOVERY", message = "eligibility must be ELIGIBLE or IN_RECOVERY")
        String eligibility,
        @RequestParam(defaultValue = "lastDonation")
        @Pattern(regexp = "lastDonation|name", message = "sort must be lastDonation or name")
        String sort,
        @RequestParam(required = false) UUID bloodBankId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return donorRosterService.listRoster(page, size, q, bloodType, eligibility, sort, bloodBankId, principal);
    }

    /** One donor's history at this bank only, newest first, most recent 50;
     * 404 if none here, never hinting the donor exists elsewhere. */
    @GetMapping("/{donorId}/donations")
    public List<DonationAtBankItem> donationHistory(
        @PathVariable UUID donorId,
        @RequestParam(required = false) UUID bloodBankId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return donorRosterService.getDonationHistoryAtBank(donorId, bloodBankId, principal);
    }
}
