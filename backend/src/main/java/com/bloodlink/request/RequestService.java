package com.bloodlink.request;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.BloodRequestDecline;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestCancellationReason;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CancelRequestPayload;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.HospitalRequestSummaryResponse;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Hospital requests filled cooperatively by any bank with stock, or declined (Figure 3.5): Business Rules 20, 6 and 11.
 * Which hospital raised a request and which bank acts are read from the caller's own User row, never the body.
 */
@Service
public class RequestService {

    /** The size of blood_requests.cancellation_note (V32). */
    private static final int CANCELLATION_NOTE_MAX_LENGTH = 200;

    /** The two statuses a request still has units outstanding in. */
    private static final List<RequestStatus> OPEN_STATUSES =
        List.of(RequestStatus.PENDING, RequestStatus.PARTIALLY_FULFILLED);

    private final BloodRequestRepository bloodRequestRepository;
    private final BatchStockService batchStockService;
    private final BloodRequestDeclineRepository declineRepository;
    private final BloodRequestContributionRepository contributionRepository;
    private final BloodBankRepository bloodBankRepository;
    private final UserRepository userRepository;
    private final ActingBankResolver actingBankResolver;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    RequestService(
        BloodRequestRepository bloodRequestRepository,
        BatchStockService batchStockService,
        BloodRequestDeclineRepository declineRepository,
        BloodRequestContributionRepository contributionRepository,
        BloodBankRepository bloodBankRepository,
        UserRepository userRepository,
        ActingBankResolver actingBankResolver,
        NotificationService notificationService,
        AuditLogService auditLogService,
        Clock clock
    ) {
        this.bloodRequestRepository = bloodRequestRepository;
        this.batchStockService = batchStockService;
        this.declineRepository = declineRepository;
        this.contributionRepository = contributionRepository;
        // Used for one question only: how many blood banks exist, which is what
        // "every bank has declined this" is measured against (see reject).
        this.bloodBankRepository = bloodBankRepository;
        this.userRepository = userRepository;
        this.actingBankResolver = actingBankResolver;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * "Submit Blood Request": a new PENDING request for the caller's own hospital, HOSPITAL_STAFF only;
     * the payload has no hospital id to tamper with.
     */
    @Transactional
    public BloodRequestResponse createRequest(CreateRequestPayload payload, AuthenticatedUser principal) {
        if (principal.getRole() != Role.HOSPITAL_STAFF) {
            // ADMIN is excluded too: an administrator belongs to no hospital to attribute the request to.
            throw new AccessDeniedException("Only hospital staff can submit a blood request.");
        }

        User user = requireUser(principal);
        Hospital hospital = user.getHospital();
        if (hospital == null) {
            // An account not yet attached to a hospital has access to nothing, as in InventoryService.
            throw new AccessDeniedException("This account is not assigned to a hospital.");
        }

        BloodRequest request = new BloodRequest();
        request.setHospital(hospital);
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        request.setBloodType(BloodType.fromLabel(payload.bloodType().trim()));
        request.setUnitsRequested(payload.unitsRequested());
        request.setUrgencyLevel(payload.urgencyLevel());
        request.setStatus(RequestStatus.PENDING);
        request.setPatientReference(blankToNull(payload.patientReference()));
        // createdAt is set by the entity's @PrePersist.

        BloodRequest saved = bloodRequestRepository.save(request);

        // Puts the request in front of blood bank staff in real time, without waiting for a refresh.
        notificationService.notifyNewRequest(saved);

        return BloodRequestResponse.from(saved);
    }

    /**
     * The dashboard feed: a hospital's own OPEN requests, a bank's open undeclined ones, or everything for ADMIN.
     * Finished requests are on Request history (Business Rule 36). bloodBankId is a view filter, never a grant.
     */
    @Transactional(readOnly = true)
    public List<BloodRequestResponse> listRequests(AuthenticatedUser principal, UUID bloodBankId) {
        List<BloodRequest> requests = switch (principal.getRole()) {
            case HOSPITAL_STAFF -> {
                User user = requireUser(principal);
                yield user.getHospital() == null
                    ? List.of()
                    : bloodRequestRepository.findOpenForHospital(user.getHospital().getId());
            }
            case BLOOD_BANK_STAFF -> {
                User user = requireUser(principal);
                UUID scope = user.getBloodBank() != null ? user.getBloodBank().getId() : bloodBankId;
                yield scope == null ? List.of() : bloodRequestRepository.findActionableForBank(scope);
            }
            case ADMIN -> bloodBankId == null
                ? bloodRequestRepository.findAllByOrderByCreatedAtDescIdDesc()
                : bloodRequestRepository.findActionableForBank(bloodBankId);
            // DONOR never reaches here (SecurityConfig), but the switch is exhaustive
            // so a new role fails the build instead of seeing everything.
            case DONOR -> List.of();
        };

        return withContributions(requests, principal.getRole() == Role.BLOOD_BANK_STAFF);
    }

    /**
     * The Hospital dashboard's four stat cards for the caller's own hospital, counted in the database over open and
     * finished requests alike, so they mean what they did before the list held open requests only (Business Rule 36).
     */
    @Transactional(readOnly = true)
    public HospitalRequestSummaryResponse hospitalSummary(AuthenticatedUser principal) {
        User user = principal.getRole() == Role.HOSPITAL_STAFF ? requireUser(principal) : null;
        if (user == null || user.getHospital() == null) {
            throw new AccessDeniedException("Only hospital staff assigned to a hospital have a request summary.");
        }
        UUID hospitalId = user.getHospital().getId();

        // "This month" is the UTC calendar month, Sierra Leone's own time.
        YearMonth month = YearMonth.now(clock);
        Instant monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant nextMonthStart = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return new HospitalRequestSummaryResponse(
            bloodRequestRepository.countByHospitalIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                hospitalId, monthStart, nextMonthStart),
            bloodRequestRepository.countByHospitalIdAndStatusIn(hospitalId, OPEN_STATUSES),
            contributionRepository.sumUnitsForHospital(hospitalId),
            bloodRequestRepository.countByHospitalIdAndUrgencyLevelAndStatusIn(
                hospitalId, UrgencyLevel.EMERGENCY, OPEN_STATUSES)
        );
    }

    /**
     * Attaches each request's contributions, in one grouped query for the whole page rather than one per row.
     * Bank staff get the bank shape, without a cancellation's note or the staff member's name (Business Rule 35).
     */
    private List<BloodRequestResponse> withContributions(List<BloodRequest> requests, boolean bankView) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<BloodRequestContribution>> byRequest = contributionRepository
            .findForRequests(requests.stream().map(BloodRequest::getId).toList()).stream()
            .collect(Collectors.groupingBy(contribution -> contribution.getBloodRequest().getId()));

        return requests.stream()
            .map(request -> {
                List<BloodRequestContribution> contributions = byRequest.getOrDefault(request.getId(), List.of());
                return bankView
                    ? BloodRequestResponse.forBank(request, contributions)
                    : BloodRequestResponse.from(request, contributions);
            })
            .toList();
    }

    /**
     * Contribute to or decline a request (Business Rule 20); the row is loaded under a write lock first,
     * so simultaneous actions take turns and the second sees the first's units.
     */
    @Transactional
    public BloodRequestResponse act(UUID requestId, RequestActionPayload payload, AuthenticatedUser principal) {
        // CONTRIBUTE moves stock, so own-bank active staff only, never ADMIN (Business Rule 8); REJECT keeps the old rule.
        // Decided before the lock; the only place ADMIN is refused a contribution, as SecurityConfig can't see the body.
        BloodBank actingBank = payload.action() == RequestAction.CONTRIBUTE
            ? actingBankResolver.resolveForStockChange(principal, "contribute units to a blood request")
            : actingBankResolver.resolve(payload.bloodBankId(), principal, "act on a blood request");

        BloodRequest request = bloodRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new NotFoundException("No blood request found for this id."));

        switch (payload.action()) {
            case CONTRIBUTE -> contribute(request, actingBank, payload.units());
            case REJECT -> reject(request, actingBank);
        }

        BloodRequest saved = bloodRequestRepository.save(request);

        // The hospital is told only after the action succeeded, and the push waits for commit.
        // A non-final decline leaves the request PENDING and sends nothing.
        notificationService.notifyRequestStatusChange(saved);

        return BloodRequestResponse.from(saved, contributionRepository.findForRequests(List.of(saved.getId())));
    }

    /**
     * The raising hospital withdraws its own open request (Business Rule 35). The row is locked first, so exactly one of
     * a cancel and a contribution wins. Supplied units and stock are untouched; only the remaining part is cancelled.
     */
    @Transactional
    public BloodRequestResponse cancel(UUID requestId, CancelRequestPayload payload, AuthenticatedUser principal) {
        // Refused here as well as in SecurityConfig, so an ADMIN can never cancel on a hospital's behalf.
        User user = principal.getRole() == Role.HOSPITAL_STAFF ? requireUser(principal) : null;
        if (user == null || user.getStatus() != AccountStatus.ACTIVE || user.getHospital() == null) {
            throw new AccessDeniedException("Only active staff of the hospital that made a request can cancel it.");
        }
        Hospital hospital = user.getHospital();

        RequestCancellationReason reason = requireCancellationReason(payload.reason());
        String note = blankToNull(payload.note());
        checkCancellationNote(reason, note);

        // Another hospital's request is a 404 like a missing one: hospitals see only their own (Business Rule 9).
        BloodRequest request = bloodRequestRepository.findByIdForUpdate(requestId)
            .filter(found -> found.getHospital().getId().equals(hospital.getId()))
            .orElseThrow(() -> new NotFoundException("No blood request found for this id."));

        // Judged after the lock is granted, so a request filled a moment ago is seen as filled.
        if (!request.getStatus().isOpen()) {
            throw new ConflictException(switch (request.getStatus()) {
                case FULFILLED -> "This request was just fully supplied, so it can no longer be cancelled.";
                case REJECTED -> "Every blood bank declined this request, so it is already closed.";
                default -> "This request has already been cancelled.";
            });
        }

        Instant now = clock.instant();
        int supplied = contributionRepository.sumUnitsForRequest(request.getId());
        request.cancel(reason, note, user, now);
        BloodRequest saved = bloodRequestRepository.save(request);

        notificationService.notifyRequestCancelled(saved);
        auditLogService.recordRequestCancelled(user, saved, cancellationDetails(saved, supplied), now);

        return BloodRequestResponse.from(saved, contributionRepository.findForRequests(List.of(saved.getId())));
    }

    /** The audit entry's words. Leaves out the free-text note and the ward reference, which may name a patient. */
    private static String cancellationDetails(BloodRequest request, int supplied) {
        String stock = supplied == 0
            ? "No units had been supplied."
            : "%d of %d units had been supplied and stay supplied.".formatted(supplied, request.getUnitsRequested());
        return "Cancelled request #%s (%s, %d %s) for %s. Reason: %s. %s".formatted(
            request.getReference(), request.getBloodType().getLabel(), request.getUnitsRequested(),
            request.getUnitsRequested() == 1 ? "unit" : "units", request.getHospital().getName(),
            request.getCancellationReason().getLabel(), stock
        );
    }

    private static RequestCancellationReason requireCancellationReason(String reason) {
        return RequestCancellationReason.fromName(reason == null ? null : reason.trim())
            .orElseThrow(() -> new InvalidFieldException(
                "reason", "Reason must be one of " + RequestCancellationReason.allNames() + "."));
    }

    /** The note's two rules, a 400 on the field: required for OTHER, and no longer than the column. */
    private static void checkCancellationNote(RequestCancellationReason reason, String note) {
        if (reason.requiresNote() && note == null) {
            throw new InvalidFieldException("note", "Add a note explaining why this request is being cancelled.");
        }
        if (note != null && note.length() > CANCELLATION_NOTE_MAX_LENGTH) {
            throw new InvalidFieldException(
                "note", "A note must be at most " + CANCELLATION_NOTE_MAX_LENGTH + " characters.");
        }
    }

    /**
     * One bank issues units to a request (Business Rule 20): request must be open and not declined by this bank;
     * units capped at what is outstanding; usable batches locked and taken FEFO; everything written in one transaction.
     */
    private void contribute(BloodRequest request, BloodBank actingBank, Integer requestedUnits) {
        if (requestedUnits == null || requestedUnits <= 0) {
            // Catches the units field being absent (it is nullable because REJECT doesn't use it).
            throw new InvalidFieldException("units", "Enter how many units this blood bank is contributing.");
        }
        if (request.getStatus() == RequestStatus.CANCELLED) {
            throw new ConflictException("This request was just cancelled, so it cannot take more units.");
        }
        if (!request.getStatus().isOpen()) {
            throw new ConflictException(
                "This request is already %s — it cannot take more units."
                    .formatted(request.getStatus().name().toLowerCase().replace('_', ' '))
            );
        }
        if (declineRepository.existsByBloodRequestIdAndBloodBankId(request.getId(), actingBank.getId())) {
            throw new ConflictException(
                "This blood bank has already declined this request, so it cannot contribute to it."
            );
        }

        int alreadyContributed = contributionRepository.sumUnitsForRequest(request.getId());
        int outstanding = request.getUnitsRequested() - alreadyContributed;
        if (outstanding <= 0) {
            // Reachable only if a concurrent contribution completed the request before the lock was granted.
            throw new ConflictException("This request has already been covered in full.");
        }
        int units = Math.min(requestedUnits, outstanding);

        BatchStockService.Issue issue;
        try {
            issue = batchStockService.issue(actingBank.getId(), request.getBloodType(), units);
        } catch (BatchStockService.InsufficientStockException shortOfStock) {
            throw insufficientStock(request, shortOfStock.getUsableUnits(), units, shortOfStock.getExpiredUnits());
        }

        // One instant for the contribution and, if it completes the request, its closing time (Business Rule 36).
        Instant now = clock.instant();
        BloodRequestContribution contribution = new BloodRequestContribution();
        contribution.setBloodRequest(request);
        contribution.setBloodBank(actingBank);
        contribution.setUnitsContributed(units);
        contribution.setContributedAt(now);
        contributionRepository.save(contribution);
        // Which batches the units came from. Refuses (rolling everything back) if the
        // draws do not add up to the contribution's units.
        batchStockService.recordAllocations(contribution, issue);

        int contributedNow = alreadyContributed + units;
        // Status is derived, never declared: whatever the arithmetic says is what
        // the request is. That is why there is no "mark fulfilled" action anywhere.
        if (contributedNow >= request.getUnitsRequested()) {
            request.close(RequestStatus.FULFILLED, now);
        } else {
            request.setStatus(RequestStatus.PARTIALLY_FULFILLED);
        }
        request.setLastContributedByBloodBank(actingBank);

        // Issuing units is the main way a group falls into Low or Critical, so the low-stock alert is raised here,
        // after every check has passed.
        notificationService.notifyStockCrossing(
            actingBank, request.getBloodType(), issue.usableBefore(), issue.usableAfter()
        );

        // The hospital hears of units that didn't complete the request; a completing one is announced in act().
        if (request.getStatus() == RequestStatus.PARTIALLY_FULFILLED) {
            notificationService.notifyRequestPartlyFilled(request, actingBank, units, contributedNow);
        }

        // The other banks still watching are told what is left; the contributing bank already has its answer.
        notificationService.notifyRequestProgress(request, actingBank, units, contributedNow);
    }

    /**
     * One bank declines a request (Business Rule 11): a per-bank row; REJECTED only once every bank has declined.
     * A contributor may not decline, and a request with units never closes by decline (Business Rule 20).
     */
    private void reject(BloodRequest request, BloodBank actingBank) {
        if (request.getStatus() == RequestStatus.CANCELLED) {
            throw new ConflictException("This request was just cancelled, so there is nothing to decline.");
        }
        if (!request.getStatus().isOpen()) {
            throw new ConflictException(
                "A request can only be declined while it is still open — this one is %s."
                    .formatted(request.getStatus().name().toLowerCase().replace('_', ' '))
            );
        }

        if (contributionRepository.existsByBloodRequestIdAndBloodBankId(request.getId(), actingBank.getId())) {
            throw new ConflictException(
                "This blood bank has already contributed to this request, so it cannot decline it."
            );
        }

        if (declineRepository.existsByBloodRequestIdAndBloodBankId(request.getId(), actingBank.getId())) {
            // Checked so a double-click gets a clear 409; the unique constraint is still the real guard.
            throw new ConflictException("This blood bank has already declined this request.");
        }

        // One instant for the decline and, if it is the last one, the request's closing time (Business Rule 36).
        Instant now = clock.instant();
        BloodRequestDecline decline = new BloodRequestDecline();
        decline.setBloodRequest(request);
        decline.setBloodBank(actingBank);
        decline.setDeclinedAt(now);
        declineRepository.save(decline);

        // Counted after the save so this decline is included — the question is
        // "have all banks now declined", not "had they before this one".
        long declines = declineRepository.countByBloodRequestId(request.getId());
        long banks = bloodBankRepository.count();

        // >= so a request stays closable if a bank is ever removed after declining; PENDING only,
        // since a request that has received units stays open.
        if (declines >= banks && request.getStatus() == RequestStatus.PENDING) {
            request.close(RequestStatus.REJECTED, now);
        }
    }

    /** 409 with the actual figures, naming the units this bank was trying to contribute
     * (possibly fewer than requested). */
    /**
     * The 409 when usable stock cannot cover a contribution, naming usable, attempted and expired units
     * so the operator understands the shortfall.
     */
    private ConflictException insufficientStock(BloodRequest request, int usable, int attempted, int expired) {
        return new ConflictException(
            "Not enough usable %s in stock — %d unit%s available, %d being contributed (%d expired unit%s cannot be issued)."
                .formatted(
                    request.getBloodType().getLabel(),
                    usable,
                    usable == 1 ? "" : "s",
                    attempted,
                    expired,
                    expired == 1 ? "" : "s"
                )
        );
    }

    private User requireUser(AuthenticatedUser principal) {
        return userRepository.findById(principal.getId())
            .orElseThrow(() -> new NotFoundException("Account not found."));
    }

    /** An untouched optional text input arrives as "" rather than absent; store the
     *  absence rather than an empty string so "no reference given" is one value. */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
