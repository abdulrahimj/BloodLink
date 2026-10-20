package com.bloodlink.appeal;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.appeal.dto.AppealReachResponse;
import com.bloodlink.appeal.dto.DonationAppealPayload;
import com.bloodlink.appeal.dto.DonationAppealResponse;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * "Send donation appeal" for bank staff (and ADMIN for a named bank); no hospital or donor route (Business Rule 1).
 * No list/amend/withdraw: an appeal is an event stored only as Notification rows; the one GET previews reach.
 */
@RestController
@RequestMapping("/api/appeals")
public class AppealController {

    private final AppealService appealService;

    AppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    /**
     * How far an appeal for one group would reach, without sending it: the figures behind the Send Appeal confirmation.
     * bloodType is the clinical label, which the client must URL-encode ("A+" becomes "A%2B").
     */
    // Deliberately no @Validated: Spring MVC validates @RequestParam constraints natively, as the handler expects.
    // @Validated would route them through AOP method validation instead, whose exception surfaced as an opaque 500.
    @GetMapping("/reach")
    public AppealReachResponse reach(
        @RequestParam
        @NotBlank(message = "Blood group is required")
        @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
        String bloodType
    ) {
        return appealService.previewReach(bloodType);
    }

    /**
     * Appeals to every eligible donor of the named blood group; 201 with a receipt naming the bank
     * and how many donors were written to.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DonationAppealResponse send(
        @Valid @RequestBody DonationAppealPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return appealService.sendAppeal(payload, principal);
    }
}
