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
 * Blood requests, hospital to blood bank (Figure 3.5); SecurityConfig admits hospital staff, bank staff and ADMIN,
 * and RequestService decides what each may do and see.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;

    RequestController(RequestService requestService) {
        this.requestService = requestService;
    }

    /**
     * "Submit Blood Request" for the caller's own hospital (the body has no hospital id);
     * 201 with the created request.
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
     * The request feed for the caller, newest first: a hospital's own requests or a bank's actionable scope.
     * bloodBankId only matters for an ADMIN or unassigned staff; assigned staff always see their own bank.
     */
    @GetMapping
    public List<BloodRequestResponse> list(
        @RequestParam(required = false) UUID bloodBankId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return requestService.listRequests(principal, bloodBankId);
    }

    /**
     * Contribute units to a request, or decline it (Business Rule 20); PATCH, as the request itself is not replaced.
     * One endpoint for both answers, returning the request in the shape the panel renders.
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
