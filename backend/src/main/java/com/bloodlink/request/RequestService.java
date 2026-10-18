package com.bloodlink.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.BloodRequestDecline;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The hospital-request-to-blood-bank sequence (Figure 3.5): a hospital raises a
 * request, and any blood bank with stock contributes units towards it until it is
 * covered — or declines it.
 *
 * Five rules from CLAUDE.md shape almost everything here:
 *   • Business Rule 20 — a request is filled cooperatively. Each contribution
 *     deducts that bank's stock immediately and is recorded as its own row; the
 *     status follows the arithmetic (PARTIALLY_FULFILLED while units remain,
 *     FULFILLED when none do). See {@link #contribute}.
 *   • Business Rule 6 — any blood bank may contribute to any hospital's request;
 *     there is no fixed hospital-to-bank ownership, and since Business Rule 20 no
 *     bank claims a request exclusively either.
 *   • Business Rule 11 — a rejection is per bank. One bank declining removes the
 *     request from that bank's own queue and nothing more; the request stays open
 *     and actionable everywhere else, and only becomes REJECTED once every blood
 *     bank has declined a request nothing has been contributed to. See
 *     {@link #reject}.
 *   • Business Rule 5's lifecycle, as Business Rule 20 restates it: PENDING ->
 *     PARTIALLY_FULFILLED -> FULFILLED (or straight to FULFILLED), or PENDING ->
 *     REJECTED.
 *   • "Never trust the frontend alone for access control" — which hospital raised a
 *     request, and which bank is acting on it, are both read from the caller's own
 *     User row, never from the request body.
 */
@Service
public class RequestService {

    private final BloodRequestRepository bloodRequestRepository;
    private final BatchStockService batchStockService;
    private final BloodRequestDeclineRepository declineRepository;
    private final BloodRequestContributionRepository contributionRepository;
    private final BloodBankRepository bloodBankRepository;
    private final UserRepository userRepository;
    private final ActingBankResolver actingBankResolver;
    private final NotificationService notificationService;

    RequestService(
        BloodRequestRepository bloodRequestRepository,
        BatchStockService batchStockService,
        BloodRequestDeclineRepository declineRepository,
        BloodRequestContributionRepository contributionRepository,
        BloodBankRepository bloodBankRepository,
        UserRepository userRepository,
        ActingBankResolver actingBankResolver,
        NotificationService notificationService
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

        return withContributions(requests);
    }

    /**
     * Attaches each request's contributions to it for the wire.
     *
     * One grouped query for the whole page rather than one per request: every row
     * on both portals now shows its progress ("2 of 4 units received"), so the
     * naive version would be a query per row on the two screens people keep open
     * all day.
     */
    private List<BloodRequestResponse> withContributions(List<BloodRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<BloodRequestContribution>> byRequest = contributionRepository
            .findForRequests(requests.stream().map(BloodRequest::getId).toList()).stream()
            .collect(Collectors.groupingBy(contribution -> contribution.getBloodRequest().getId()));

        return requests.stream()
            .map(request -> BloodRequestResponse.from(request, byRequest.getOrDefault(request.getId(), List.of())))
            .toList();
    }

    /**
     * Contribute units to a request, or decline it — the two things a blood bank can
     * do with one (Business Rule 20).
     *
     * The row is loaded under a write lock before anything is read from it, and that
     * lock is the concurrency guarantee for the whole feature. Every bank sees every
     * open request now, so two banks acting on the same one at the same moment is
     * ordinary rather than rare; the lock makes them take turns, so the second one
     * reads a total that already includes the first one's units. See
     * {@link #contribute} for what it would otherwise cost.
     */
    @Transactional
    public BloodRequestResponse act(UUID requestId, RequestActionPayload payload, AuthenticatedUser principal) {
        // CONTRIBUTE takes units off a bank's shelves, so it is a stock change: active
        // staff of their own bank only, and never an ADMIN (Business Rule 8, Stage
        // 3b-2). REJECT moves no blood and keeps the older rule, under which an ADMIN
        // may decline on a named bank's behalf. Decided before the request row is
        // locked or read, so a refused contribution touches nothing.
        //
        // This is the only place ADMIN is refused a contribution: CONTRIBUTE and
        // REJECT share PATCH /api/requests/{id}, and a URL rule in SecurityConfig
        // cannot see which one the body asks for.
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

        // Told to the hospital that raised it, and only after the action above has
        // succeeded — an illegal transition or a stock shortfall throws before
        // reaching here, so a hospital is never told units arrived that did not. The
        // push itself waits for the commit as well; see NotificationPublisher.
        //
        // A decline that was not the last one outstanding leaves the request open,
        // and notifyRequestStatusChange deliberately sends nothing while it is
        // PENDING: the request is still live with the other banks, so "a bank
        // declined" would read as bad news about something that has lost nothing.
        // They are told only if the last bank declines and the request really closes.
        notificationService.notifyRequestStatusChange(saved);

        return BloodRequestResponse.from(saved, contributionRepository.findForRequests(List.of(saved.getId())));
    }

    /**
     * One bank issues units towards a request — the act at the centre of Business
     * Rule 20, and the only place stock moves out of a bank.
     *
     * Replaces the old approve-then-fulfil pair. Approving claimed a request
     * exclusively and promised the whole amount, which is precisely what stopped two
     * banks holding 2 units each from filling one request for 4. A contribution
     * promises nothing and claims nothing: it issues what this bank can spare now,
     * and leaves whatever remains open to everyone, this bank included.
     *
     * What happens, in order, and why in that order:
     *   1. The request must still be open. A FULFILLED or REJECTED one takes no more
     *      units, and saying so is more useful than silently recording a no-op.
     *   2. This bank must not have declined it. A decline is this bank's final
     *      answer on this request (see {@link #reject}), and the request is not in
     *      its queue any more — so a contribution could only arrive by a stale
     *      screen or a hand-made call.
     *   3. The units are capped at what is genuinely still outstanding, read *after*
     *      the lock above was granted. This is the race the lock exists for: without
     *      it two banks would both read "4 needed", both contribute 4, and a
     *      hospital that asked for 4 units would be sent 8 real units of blood while
     *      two banks' shelves emptied. Capping rather than refusing is deliberate —
     *      between the operator reading the screen and pressing the button, another
     *      bank may have taken part of it, and that is not their mistake to be
     *      punished for. The response says what was actually recorded.
     *   4. This bank's USABLE batches of the group are locked, in issue order
     *      (earliest expiry, then id), and must cover the capped amount; units in
     *      expired batches never count (Business Rule 24). The request row was locked
     *      first, in act(), and batches second: that is the one lock order anywhere,
     *      so no two transactions can each hold what the other needs. Asking for more
     *      than this bank holds *is* refused (rather than capped again): that is a
     *      mistake about its own shelf, and quietly issuing fewer units than the
     *      operator typed would leave them believing they had sent more than they
     *      did. The refusal names the usable units, the units asked for, and the
     *      expired units that could not be counted.
     *   5. The units are taken first expiry, first out (FefoAllocator), so blood is
     *      used before it can go to waste.
     *   6. The deductions, the contribution row, one allocation row per batch drawn
     *      on (which batches the units came from, adding up to units_contributed),
     *      and the new status are written together. One transaction covers them all,
     *      so units can never leave stock without being recorded, nor be recorded
     *      without leaving stock.
     */
    private void contribute(BloodRequest request, BloodBank actingBank, Integer requestedUnits) {
        if (requestedUnits == null || requestedUnits <= 0) {
            // Bean Validation catches a zero or negative number; this catches the
            // field being absent entirely, which no annotation on a nullable field
            // can (it is nullable precisely because REJECT does not use it).
            throw new InvalidFieldException("units", "Enter how many units this blood bank is contributing.");
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
            // Reachable only if a concurrent contribution completed the request
            // between this caller reading the screen and the lock being granted —
            // the status check above passed on a row that was still open when it
            // was read. The lock is what makes this the worst case rather than an
            // over-contribution.
            throw new ConflictException("This request has already been covered in full.");
        }
        int units = Math.min(requestedUnits, outstanding);

        BatchStockService.Issue issue;
        try {
            issue = batchStockService.issue(actingBank.getId(), request.getBloodType(), units);
        } catch (BatchStockService.InsufficientStockException shortOfStock) {
            throw insufficientStock(request, shortOfStock.getUsableUnits(), units, shortOfStock.getExpiredUnits());
        }

        BloodRequestContribution contribution = new BloodRequestContribution();
        contribution.setBloodRequest(request);
        contribution.setBloodBank(actingBank);
        contribution.setUnitsContributed(units);
        // contributedAt is set by the entity's @PrePersist.
        contributionRepository.save(contribution);
        // Which batches the units came from. Refuses (rolling everything back) if the
        // draws do not add up to the contribution's units.
        batchStockService.recordAllocations(contribution, issue);

        int contributedNow = alreadyContributed + units;
        // Status is derived, never declared: whatever the arithmetic says is what
        // the request is. That is why there is no "mark fulfilled" action anywhere.
        request.setStatus(contributedNow >= request.getUnitsRequested()
            ? RequestStatus.FULFILLED
            : RequestStatus.PARTIALLY_FULFILLED);
        request.setLastContributedByBloodBank(actingBank);

        // Issuing units is the ordinary way a group falls into Low or Critical, so
        // this is the low-stock alert's main trigger. Raised here rather than inside
        // the inventory save so the alert reflects the deduction that actually
        // happened, after every check above has passed.
        notificationService.notifyStockCrossing(
            actingBank, request.getBloodType(), issue.usableBefore(), issue.usableAfter()
        );

        // The hospital hears about units that arrived without completing the
        // request; a contribution that does complete it is announced as a
        // fulfilment instead, by notifyRequestStatusChange in act() above.
        if (request.getStatus() == RequestStatus.PARTIALLY_FULFILLED) {
            notificationService.notifyRequestPartlyFilled(request, actingBank, units, contributedNow);
        }

        // And the other banks still watching this request are told what is left, so
        // two of them cannot each set out to cover the same shrinking gap. The
        // contributing bank is not told — it has the answer in its own response.
        notificationService.notifyRequestProgress(request, actingBank, units, contributedNow);
    }

    /**
     * One bank declines a request — Business Rule 11's per-bank rejection.
     *
     * This used to set status = REJECTED outright, which closed the request for
     * every bank because status is a single shared field. A bank that happened to be
     * out of a group could therefore kill a request another bank could have filled.
     * Now the decline is recorded as its own row against (request, bank), and the
     * request's status is left PENDING: it drops out of THIS bank's queue (see
     * BloodRequestRepository.findActionableForBank) and stays fully actionable for
     * every other bank.
     *
     * The request only becomes REJECTED — genuinely closed, for the hospital as well
     * — once every blood bank has declined it. "Every blood bank" is the count of
     * blood_banks rows: the seeded banks are the operating banks, and nothing in the
     * agreed schema marks one inactive, so existing is what active means here. If an
     * inactive flag is ever added, this comparison is the one place that has to learn
     * about it.
     *
     * last_contributed_by_blood_bank is never touched here: it names a bank that
     * gave units, and a decline gives none.
     *
     * Business Rule 20 adds two things to this, and changes nothing else.
     *
     * First, a bank that has contributed to a request may not then decline it. It
     * has already given what it could; a decline on top would be a second, opposite
     * answer to the same question, and — because declines are counted towards
     * closing a request — one that could help close a request this very bank had
     * just helped fill.
     *
     * Second, a request that has received units never closes by decline, however
     * many banks decline the rest of it. Real progress exists on it, the hospital
     * has blood it can use, and stock that could complete it may arrive tomorrow;
     * closing it would throw that away and force the ward to start again. A request
     * nothing has been contributed to still closes exactly as before — it has
     * nothing to preserve, and leaving it open would be a queue entry every bank has
     * already said no to. (The status guard below is belt and braces: since a
     * contributor cannot decline, a request with contributions can never reach a
     * full set of declines in the first place.)
     */
    private void reject(BloodRequest request, BloodBank actingBank) {
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
            // Checked rather than left to the unique constraint so a double-click
            // gets a sentence the operator can act on instead of a 500 out of a
            // constraint violation. The constraint is still the real guard — it is
            // what stops two simultaneous transactions, which this check cannot see.
            throw new ConflictException("This blood bank has already declined this request.");
        }

        BloodRequestDecline decline = new BloodRequestDecline();
        decline.setBloodRequest(request);
        decline.setBloodBank(actingBank);
        // declinedAt is set by the entity's @PrePersist.
        declineRepository.save(decline);

        // Counted after the save so this decline is included — the question is
        // "have all banks now declined", not "had they before this one".
        long declines = declineRepository.countByBloodRequestId(request.getId());
        long banks = bloodBankRepository.count();

        // >= rather than ==: if a bank were ever removed after declining, the
        // decline count could exceed the bank count, and a request no remaining
        // bank can see must still be closable rather than stuck PENDING forever.
        //
        // PENDING only, never PARTIALLY_FULFILLED — see this method's comment: a
        // request that has already received units stays open indefinitely.
        if (declines >= banks && request.getStatus() == RequestStatus.PENDING) {
            request.setStatus(RequestStatus.REJECTED);
        }
    }

    /** 409 with the actual figures, so the operator can see how short they are
     *  rather than being told only that it failed. Names the units this bank was
     *  trying to contribute — which may be fewer than the request asked for, since
     *  another bank may already have covered part of it. */
    /**
     * The 409 for a contribution this bank's usable stock cannot cover. Names the
     * usable units, the units being contributed, and the expired units on the shelf
     * that could not be counted, so an operator who can see 12 bags in the fridge
     * understands why the system says 9.
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
