package com.bloodlink.donor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.donor.dto.DonorLookupResponse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Donor lookup for the "Record Donation" flow.
 *
 * Sits on /api/donors but is restricted to BLOOD_BANK_STAFF and ADMIN, which is
 * the opposite of the rest of that path — SecurityConfig matches this one route
 * ahead of the general /api/donors/** rule for exactly that reason. A donor
 * must not be able to look up another donor, and bank staff must be able to
 * look up the one in front of them; neither rule fits the other's route.
 *
 * Kept out of {@link DonorController}, whose every route is scoped to the caller
 * by their own token. Putting a lookup-by-someone-else in there would make that
 * class's guarantee untrue.
 */
@RestController
@RequestMapping("/api/donors")
public class DonorLookupController {

    private final DonorLookupService donorLookupService;

    DonorLookupController(DonorLookupService donorLookupService) {
        this.donorLookupService = donorLookupService;
    }

    /**
     * GET /api/donors/lookup?phone=... — exact match, 404 when nobody matches.
     *
     * A blank or missing phone is refused with its own 400 rather than reaching
     * the service to become a slightly misleading 404 ("no donor found" for a
     * number nobody typed) — the two failures mean different things, and only one
     * of them is "search again with a different number".
     *
     * No @Validated on this class — see the identical note on AppealController.
     * reach, which found out the hard way that it is not needed and actively
     * wrong: Spring MVC validates an annotated @RequestParam like this one
     * natively (6.1+), and @Validated would only divert it through the older
     * AOP-based mechanism instead, which this project's GlobalExceptionHandler
     * does not shape the same way.
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
