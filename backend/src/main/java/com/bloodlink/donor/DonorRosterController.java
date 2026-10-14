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
 * The Donors page's data: who has given at this blood bank, and each of their
 * donations here — Business Rule 21.
 *
 * Restricted to {@code BLOOD_BANK_STAFF} and {@code ADMIN} by SecurityConfig,
 * matched ahead of the general {@code /api/donors/**} rule the same way
 * {@code /api/donors/lookup} already is: this is bank staff looking at
 * donors, the opposite of every other route under {@code /api/donors}.
 *
 * {@code bloodBankId} on both routes is meaningful only for an ADMIN, who
 * belongs to no bank and must name one; for {@code BLOOD_BANK_STAFF} it is
 * ignored in favour of their own assigned bank (ActingBankResolver, same as
 * every other bank-scoped action in this codebase). Sending it is harmless
 * either way.
 *
 * Deliberately no {@code @Validated} on this class — Spring MVC has validated
 * constraint-annotated {@code @RequestParam}s natively since 6.1, which is
 * what {@code GlobalExceptionHandler.handleParameterValidation} expects; see
 * {@code AppealController.reach}'s comment for why {@code @Validated} would
 * route the same constraints through the wrong exception type.
 */
@RestController
@RequestMapping("/api/donors/roster")
public class DonorRosterController {

    private final DonorRosterService donorRosterService;

    DonorRosterController(DonorRosterService donorRosterService) {
        this.donorRosterService = donorRosterService;
    }

    /**
     * One page of this bank's donor roster, newest donation first by default.
     *
     * {@code page}/{@code size} follow the Audit Log's pagination exactly
     * (0-based, default 20, capped at 100) — a bad value is a 400 naming the
     * field, never silently corrected. {@code bloodType} must be one of the
     * eight clinical labels, URL-encoded by the client since a literal "+" in
     * a query string decodes as a space (same note as AppealController.reach).
     * {@code eligibility} and {@code sort} are each one of a fixed pair of
     * values for the same reason.
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

    /** One donor's donation history at this bank only, newest first, most
     *  recent 50. 404 for a donor with no donation here — never another
     *  bank's data, and never a hint that the donor exists elsewhere. */
    @GetMapping("/{donorId}/donations")
    public List<DonationAtBankItem> donationHistory(
        @PathVariable UUID donorId,
        @RequestParam(required = false) UUID bloodBankId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return donorRosterService.getDonationHistoryAtBank(donorId, bloodBankId, principal);
    }
}
