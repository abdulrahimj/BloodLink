package com.bloodlink.request;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Blood requests — the hospital-to-blood-bank sequence in Figure 3.5.
 *
 * SecurityConfig limits this whole path to HOSPITAL_STAFF, BLOOD_BANK_STAFF and
 * ADMIN. Which of those may do what, and which rows each of them sees, is decided
 * in RequestService: a URL rule can match a role, but it cannot tell whether the
 * request being acted on belongs to the caller's own facility.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;

    RequestController(RequestService requestService) {
        this.requestService = requestService;
    }

    /**
     * "Submit Blood Request". The new request's hospital is the caller's own — the
     * body has no hospital id to supply one.
     *
     * 201 with the created request, so the tracking list can show it immediately
     * without a follow-up GET.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BloodRequestResponse create(
        @Valid @RequestBody CreateRequestPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return requestService.createRequest(payload, principal);
    }

    /**
     * The request feed for whoever is asking — a hospital's own requests, or a blood
     * bank's actionable scope. Newest first.
     *
     * {@code bloodBankId} is optional and only meaningful to an ADMIN, or to bank
     * staff not yet assigned to a bank: it picks which bank's feed to look at, the
     * same way the Blood Bank portal's picker already works for inventory. Assigned
     * staff are always scoped to their own bank regardless of what is passed.
     */
    @GetMapping
    public List<BloodRequestResponse> list(
        @RequestParam(required = false) UUID bloodBankId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return requestService.listRequests(principal, bloodBankId);
    }

    /**
     * Contribute units towards a request, or decline it (Business Rule 20).
     *
     * PATCH rather than PUT because this changes one aspect of the request (how much
     * of it is covered) and never replaces it: the hospital's blood group, units and
     * urgency are not editable once submitted.
     *
     * A contribution is deliberately not a POST to a /contributions sub-resource,
     * even though it does create one. The two actions a bank can take are two
     * answers to the same question — "can you help with this?" — taken from the same
     * row of the same panel, and splitting them across two endpoints would mean the
     * dashboard's single handler had to know which shape each one needed. What comes
     * back is the request, in the shape the panel renders, either way.
     */
    @PatchMapping("/{requestId}")
    public BloodRequestResponse act(
        @PathVariable UUID requestId,
        @Valid @RequestBody RequestActionPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return requestService.act(requestId, payload, principal);
    }
}
