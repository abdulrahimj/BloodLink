package com.bloodlink.appeal;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.appeal.dto.DonationAppealPayload;
import com.bloodlink.appeal.dto.DonationAppealResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * "Send donation appeal" — the blood bank / lab staff use case from CLAUDE.md.
 *
 * SecurityConfig already restricts /api/appeals/** to BLOOD_BANK_STAFF and ADMIN,
 * and AppealService narrows an ADMIN to a named bank and bank staff to their own.
 * There is no GET here and no route by which a hospital or a donor could send one:
 * under Business Rule 1 an appeal has exactly one kind of author.
 *
 * Only POST exists because an appeal is an event, not a record. Once sent it lives
 * as Notification rows in each recipient's bell — there is no appeals table to list,
 * amend or withdraw, and the schema deliberately has none.
 */
@RestController
@RequestMapping("/api/appeals")
public class AppealController {

    private final AppealService appealService;

    AppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    /**
     * Appeals to every donor recorded with the named blood group.
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
