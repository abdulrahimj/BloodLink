package com.bloodlink.donor;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.donor.dto.CorrectBloodGroupPayload;
import com.bloodlink.donor.dto.DonorBloodGroupResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * "Edit blood group" on the Donors page (Business Rule 28): active blood bank staff correct a donor's profile
 * group after a test. SecurityConfig and DonorBloodGroupService both refuse every other role.
 */
@RestController
@RequestMapping("/api/donors")
public class DonorBloodGroupController {

    private final DonorBloodGroupService donorBloodGroupService;

    DonorBloodGroupController(DonorBloodGroupService donorBloodGroupService) {
        this.donorBloodGroupService = donorBloodGroupService;
    }

    /** Sets or changes one donor's blood group, with a required note; 400 for the same group, 404 for no donor. */
    @PatchMapping("/{donorId}/blood-group")
    public DonorBloodGroupResponse correct(
        @PathVariable UUID donorId,
        @Valid @RequestBody CorrectBloodGroupPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return donorBloodGroupService.correct(donorId, payload, principal);
    }
}
