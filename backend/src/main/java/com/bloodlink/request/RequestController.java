package com.bloodlink.request;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
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

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CancelRequestPayload;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.HospitalRequestSummaryResponse;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.request.dto.RequestHistoryPageResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Blood requests, hospital to blood bank (Figure 3.5); SecurityConfig admits hospital staff, bank staff and ADMIN,
 * and RequestService decides what each may do and see.
 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestService requestService;
    private final RequestHistoryService requestHistoryService;

    RequestController(RequestService requestService, RequestHistoryService requestHistoryService) {
        this.requestService = requestService;
        this.requestHistoryService = requestHistoryService;
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

    /** The Hospital dashboard's four stat cards, for the caller's own hospital; HOSPITAL_STAFF only. */
    @GetMapping("/summary")
    public HospitalRequestSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser principal) {
        return requestService.hospitalSummary(principal);
    }

    /**
     * Request history (Business Rule 36): one page of finished requests, newest closed first. from/to are closed
     * dates, both inclusive (UTC); hospitalId is for bank staff only. Bad values are a 400 naming the field.
     */
    @GetMapping("/history")
    public RequestHistoryPageResponse history(
        @RequestParam(defaultValue = "0")
        @Min(value = 0, message = "page cannot be negative")
        int page,
        @RequestParam(defaultValue = "20")
        @Min(value = 1, message = "size must be at least 1")
        @Max(value = 100, message = "size cannot exceed 100")
        int size,
        @RequestParam(required = false)
        @Pattern(regexp = "FULFILLED|REJECTED|CANCELLED", message = "status must be FULFILLED, REJECTED or CANCELLED")
        String status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false)
        @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
        String bloodType,
        @RequestParam(required = false) UUID hospitalId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return requestHistoryService.history(page, size, status, from, to, bloodType, hospitalId, principal);
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

    /**
     * The raising hospital withdraws its own open request (Business Rule 35);
     * 200 with the request as it now stands.
     */
    @PostMapping("/{requestId}/cancel")
    public BloodRequestResponse cancel(
        @PathVariable UUID requestId,
        @Valid @RequestBody CancelRequestPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return requestService.cancel(requestId, payload, principal);
    }
}
