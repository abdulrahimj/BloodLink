package com.bloodlink.request;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The hospital-request-to-blood-bank sequence (Figure 3.5): a hospital raises a
 * request, a blood bank approves or rejects it, and fulfilling it moves real units
 * out of that bank's stock.
 *
 * Three rules from CLAUDE.md shape almost everything here:
 *   • Business Rule 5 — PENDING -> APPROVED -> FULFILLED, or PENDING -> REJECTED,
 *     and fulfilment must decrement the fulfilling bank's inventory.
 *   • Business Rule 6 — any blood bank may fulfil any hospital's request; there is
 *     no fixed hospital-to-bank ownership.
 *   • "Never trust the frontend alone for access control" — which hospital raised a
 *     request, and which bank is acting on it, are both read from the caller's own
 *     User row, never from the request body.
 */
@Service
public class RequestService {

    private final BloodRequestRepository bloodRequestRepository;
    private final BloodInventoryRepository bloodInventoryRepository;
    private final UserRepository userRepository;
    private final ActingBankResolver actingBankResolver;
    private final NotificationService notificationService;

    RequestService(
        BloodRequestRepository bloodRequestRepository,
        BloodInventoryRepository bloodInventoryRepository,
        UserRepository userRepository,
        ActingBankResolver actingBankResolver,
        NotificationService notificationService
    ) {
        this.bloodRequestRepository = bloodRequestRepository;
        this.bloodInventoryRepository = bloodInventoryRepository;
        this.userRepository = userRepository;
        this.actingBankResolver = actingBankResolver;
        this.notificationService = notificationService;
    }

    /**
     * "Submit Blood Request" — raises a new PENDING request for the caller's own
     * hospital.
     *
     * Only HOSPITAL_STAFF may do this, and only for the hospital on their own User
     * row: the payload carries no hospital id at all, so there is nothing a client
     * could tamper with to raise a request in another hospital's name. Status starts
     * PENDING and no fulfilling bank is set — under Business Rule 6 no bank owns the
     * request until one approves it.
     */
    @Transactional
    public BloodRequestResponse createRequest(CreateRequestPayload payload, AuthenticatedUser principal) {
        if (principal.getRole() != Role.HOSPITAL_STAFF) {
            // ADMIN is deliberately excluded too: an administrator belongs to no
            // hospital, so there is no honest hospital to attribute the request to,
            // and accepting one from the body would reopen exactly the hole the
            // no-hospital-id payload closes.
            throw new AccessDeniedException("Only hospital staff can submit a blood request.");
        }

        User user = requireUser(principal);
        Hospital hospital = user.getHospital();
        if (hospital == null) {
            // Same shape as the unassigned-bank case in InventoryService: an account
            // an administrator has not yet attached to a facility has access to
            // nothing, rather than to everything or to an arbitrary default.
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

        // Puts the request in front of blood bank staff without waiting for them to
        // refresh the dashboard. This is the whole point of the request being
        // real-time: an EMERGENCY request that sits unseen until someone happens to
        // reload the page is a paper process with extra steps.
        notificationService.notifyNewRequest(saved);

        return BloodRequestResponse.from(saved);
    }

    /**
     * The request feed, scoped to what the caller is entitled to see.
     *
     *   • HOSPITAL_STAFF — their own hospital's requests, and only those.
     *   • BLOOD_BANK_STAFF — requests their bank could still fulfil (unclaimed) plus
     *     the ones it has already acted on. See findActionableForBank for why those
     *     two sets together are what Business Rule 6 means in practice.
     *   • ADMIN — everything, or one bank's actionable scope when a bank is named.
     *
     * {@code bloodBankId} is a view filter, never a grant: it lets an ADMIN (and a
     * staff account no administrator has assigned a bank to yet) look at a specific
     * bank's feed, exactly as the Blood Bank portal's bank picker already does for
     * inventory. Assigned staff ignore it entirely — their own bank always wins, so
     * passing another bank's id cannot widen what they see. Acting on any of it is a
     * separate check, made by ActingBankResolver.
     */
    @Transactional(readOnly = true)
    public List<BloodRequestResponse> listRequests(AuthenticatedUser principal, UUID bloodBankId) {
        List<BloodRequest> requests = switch (principal.getRole()) {
            case HOSPITAL_STAFF -> {
                User user = requireUser(principal);
                yield user.getHospital() == null
                    ? List.of()
                    : bloodRequestRepository.findByHospitalIdOrderByCreatedAtDesc(user.getHospital().getId());
            }
            case BLOOD_BANK_STAFF -> {
                User user = requireUser(principal);
                UUID scope = user.getBloodBank() != null ? user.getBloodBank().getId() : bloodBankId;
                yield scope == null ? List.of() : bloodRequestRepository.findActionableForBank(scope);
            }
            case ADMIN -> bloodBankId == null
                ? bloodRequestRepository.findAllByOrderByCreatedAtDesc()
                : bloodRequestRepository.findActionableForBank(bloodBankId);
            // DONOR never reaches here — SecurityConfig keeps the role off this URL —
            // but the switch is exhaustive so adding a role later fails the build
            // rather than silently falling through to "see everything".
            case DONOR -> List.of();
        };

        return requests.stream().map(BloodRequestResponse::from).toList();
    }

    /**
     * Approve, reject or fulfil one request — a single step along Business Rule 5's
     * lifecycle, performed by a blood bank.
     *
     * The row is loaded under a write lock before its status is read, because the
     * check ("is this still PENDING?") and the write ("make it APPROVED") are
     * separate statements. Both blood banks see every unclaimed request, so two of
     * them opening the same one and clicking Approve together is an ordinary event,
     * not a rare race: without the lock both would read PENDING, both would pass the
     * check, and the second write would quietly overwrite the first.
     */
    @Transactional
    public BloodRequestResponse act(UUID requestId, RequestActionPayload payload, AuthenticatedUser principal) {
        BloodBank actingBank = actingBankResolver.resolve(payload.bloodBankId(), principal, "act on a blood request");

        BloodRequest request = bloodRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new NotFoundException("No blood request found for this id."));

        switch (payload.action()) {
            case APPROVE -> approve(request, actingBank);
            case REJECT -> reject(request);
            case FULFIL -> fulfil(request, actingBank);
        }

        BloodRequest saved = bloodRequestRepository.save(request);

        // Told to the hospital that raised it, and only after the transition above
        // has succeeded — an illegal transition or a stock shortfall throws before
        // reaching here, so a hospital is never told a request was approved when the
        // approval was refused. The push itself waits for the commit as well; see
        // NotificationPublisher.
        notificationService.notifyRequestStatusChange(saved);

        return BloodRequestResponse.from(saved);
    }

    /**
     * PENDING -> APPROVED, claiming the request for the acting bank.
     *
     * Stock is checked here as well as at fulfilment, even though nothing is deducted
     * yet. Approving is a promise to the hospital — and under Business Rule 6 it also
     * takes the request out of every other bank's feed — so letting a bank approve a
     * request it visibly cannot cover would strand the hospital behind a bank that
     * was never able to help. The deduction still happens only at fulfilment, and is
     * re-checked there against the stock at that moment.
     */
    private void approve(BloodRequest request, BloodBank actingBank) {
        requireStatus(request, RequestStatus.PENDING, "approved");
        requireSufficientStock(actingBank, request);

        request.setStatus(RequestStatus.APPROVED);
        request.setFulfilledByBloodBank(actingBank);
    }

    /**
     * PENDING -> REJECTED. Terminal, and deliberately leaves fulfilled_by_blood_bank
     * null: the column means "the bank that is supplying this", and a rejection
     * supplies nothing.
     *
     * A consequence worth being explicit about: status is a single field, so one
     * bank rejecting a request closes it for every bank, not just for itself. That is
     * the lifecycle CLAUDE.md specifies (Business Rule 5 has exactly one REJECTED
     * state), and recording per-bank refusals would need a table the agreed schema
     * does not have. The hospital can raise a fresh request.
     */
    private void reject(BloodRequest request) {
        requireStatus(request, RequestStatus.PENDING, "rejected");
        request.setStatus(RequestStatus.REJECTED);
    }

    /**
     * APPROVED -> FULFILLED, and the only place stock actually moves: the requested
     * units come off the fulfilling bank's (bank, blood group) inventory row.
     *
     * Stock is re-read under a write lock and re-checked here rather than trusted
     * from the approval, because time passes in between and the same units may have
     * been issued to someone else meanwhile. If they no longer cover the request the
     * whole transaction is refused — the status is not advanced and nothing is
     * deducted — so a bank can never report a request fulfilled out of units it does
     * not have.
     */
    private void fulfil(BloodRequest request, BloodBank actingBank) {
        requireStatus(request, RequestStatus.APPROVED, "fulfilled");

        BloodBank approvedBy = request.getFulfilledByBloodBank();
        if (approvedBy == null || !approvedBy.getId().equals(actingBank.getId())) {
            // Approval is what claims a request; another bank fulfilling it would
            // deduct from stock the hospital was never promised.
            throw new AccessDeniedException("This request was approved by another blood bank.");
        }

        BloodInventory stock = bloodInventoryRepository
            .findByBloodBankIdAndBloodTypeForUpdate(actingBank.getId(), request.getBloodType())
            .orElseThrow(() -> insufficientStock(request, 0));

        if (stock.getUnitsAvailable() < request.getUnitsRequested()) {
            throw insufficientStock(request, stock.getUnitsAvailable());
        }

        int unitsBefore = stock.getUnitsAvailable();
        stock.setUnitsAvailable(unitsBefore - request.getUnitsRequested());
        // touch() on the entity's @PreUpdate refreshes lastUpdated.
        bloodInventoryRepository.save(stock);

        request.setStatus(RequestStatus.FULFILLED);

        // Issuing units is the ordinary way a group falls into Low or Critical, so
        // this is the low-stock alert's main trigger. Raised here rather than inside
        // the inventory save so the alert reflects the deduction that actually
        // happened, after every check above has passed.
        notificationService.notifyStockCrossing(
            actingBank, request.getBloodType(), unitsBefore, stock.getUnitsAvailable()
        );
    }

    /** Guards one lifecycle edge, naming both the current and required state so the
     *  panel can show the operator why their click was refused. Phrased without an
     *  article so it reads correctly for both "approved" and "pending". */
    private void requireStatus(BloodRequest request, RequestStatus required, String verbPastTense) {
        if (request.getStatus() != required) {
            throw new ConflictException(
                "A request can only be %s while it is %s — this one is %s."
                    .formatted(verbPastTense, required.name().toLowerCase(), request.getStatus().name().toLowerCase())
            );
        }
    }

    /** Approval-time availability check; the authoritative one is in {@link #fulfil}. */
    private void requireSufficientStock(BloodBank actingBank, BloodRequest request) {
        int available = bloodInventoryRepository
            .findByBloodBankIdAndBloodType(actingBank.getId(), request.getBloodType())
            .map(BloodInventory::getUnitsAvailable)
            .orElse(0);

        if (available < request.getUnitsRequested()) {
            throw insufficientStock(request, available);
        }
    }

    /** 409 with the actual figures, so the operator can see how short they are
     *  rather than being told only that it failed. */
    private ConflictException insufficientStock(BloodRequest request, int available) {
        return new ConflictException(
            "Not enough %s in stock — %d unit%s available, %d requested."
                .formatted(
                    request.getBloodType().getLabel(),
                    available,
                    available == 1 ? "" : "s",
                    request.getUnitsRequested()
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
