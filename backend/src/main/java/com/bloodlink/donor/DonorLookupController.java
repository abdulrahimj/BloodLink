package com.bloodlink.donor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.donor.dto.DonorLookupResponse;

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

    /** GET /api/donors/lookup?phone=... — exact match, 404 when nobody matches. */
    @GetMapping("/lookup")
    public DonorLookupResponse lookup(@RequestParam String phone) {
        return donorLookupService.lookupByPhone(phone);
    }
}
