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
 * "Send donation appeal" — the blood bank / lab staff use case from CLAUDE.md.
 *
 * SecurityConfig already restricts /api/appeals/** to BLOOD_BANK_STAFF and ADMIN,
 * and AppealService narrows an ADMIN to a named bank and bank staff to their own.
 * There is no GET here and no route by which a hospital or a donor could send one:
 * under Business Rule 1 an appeal has exactly one kind of author.
 *
 * There is no route that lists, amends or withdraws an appeal, because an appeal is
 * an event rather than a record: once sent it lives as Notification rows in each
 * recipient's bell, and the schema deliberately has no appeals table. The one GET
 * here is not an exception to that — it reads the *donor register*, not past
 * appeals, and answers a question about an appeal that has not happened yet.
 */
@RestController
@RequestMapping("/api/appeals")
public class AppealController {

    private final AppealService appealService;

    AppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    /**
     * How far an appeal for one group would reach, without sending it — the figures
     * behind the "Send Appeal" confirmation on the Blood Inventory screen.
     *
     * {@code bloodType} is the clinical label ("A+", "O-"), which the client must
     * URL-encode: a literal "+" in a query string decodes as a space, so an
     * un-encoded "A+" would arrive as "A " and be rejected as an unknown group.
     * encodeURIComponent turns it into "A%2B", which decodes back correctly.
     */
    // Deliberately no @Validated on this class. Spring MVC has validated
    // constraint-annotated @RequestParam/@PathVariable values natively since
    // 6.1 — no class-level marker needed, since spring-boot-starter-validation
    // already supplies the Validator bean that mechanism looks for — and it is
    // what GlobalExceptionHandler.handleParameterValidation expects. Adding
    // @Validated here would not add coverage: it would redirect the very same
    // constraints through Spring's older, general-purpose AOP-based method
    // validation instead, which throws a plain jakarta.validation.
    // ConstraintViolationException that reached this project's catch-all
    // handler as an opaque 500 until this was caught and fixed here.
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
     * Appeals to every eligible donor recorded with the named blood group.
     *
     * 201 with a receipt naming the sending bank and how many donors were written
     * to, so the panel that sent it can confirm what actually happened rather than
     * assuming the click worked.
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
