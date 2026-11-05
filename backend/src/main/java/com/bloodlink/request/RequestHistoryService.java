package com.bloodlink.request;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.BloodRequestDecline;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.RequestHistoryEntryResponse;
import com.bloodlink.request.dto.RequestHistoryPageResponse;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

import jakarta.persistence.criteria.Subquery;

/**
 * Request history (Business Rule 36): finished requests only, newest closed first, one page at a time. A hospital
 * sees its own; a bank sees those it contributed to or declined. ADMIN uses the Audit Log and Reports instead.
 */
@Service
public class RequestHistoryService {

    /** The three statuses a request can finish in; nothing else ever appears in history. */
    static final Set<RequestStatus> FINISHED =
        EnumSet.of(RequestStatus.FULFILLED, RequestStatus.REJECTED, RequestStatus.CANCELLED);

    /** Newest closed first; id breaks ties so two requests closed in the same instant never swap pages. */
    private static final Sort NEWEST_CLOSED_FIRST = Sort.by(Sort.Order.desc("closedAt"), Sort.Order.desc("id"));

    private final BloodRequestRepository bloodRequestRepository;
    private final BloodRequestContributionRepository contributionRepository;
    private final BloodRequestDeclineRepository declineRepository;
    private final UserRepository userRepository;

    RequestHistoryService(
        BloodRequestRepository bloodRequestRepository,
        BloodRequestContributionRepository contributionRepository,
        BloodRequestDeclineRepository declineRepository,
        UserRepository userRepository
    ) {
        this.bloodRequestRepository = bloodRequestRepository;
        this.contributionRepository = contributionRepository;
        this.declineRepository = declineRepository;
        this.userRepository = userRepository;
    }

    /** Whose history is being read: exactly one of the two ids is set. */
    private record Scope(UUID hospitalId, UUID bloodBankId) {
        boolean isBank() {
            return bloodBankId != null;
        }
    }

    /**
     * One page of the caller's history, filtered as asked. page, size and status were checked by the controller;
     * from/to are closed dates (UTC, both inclusive) and a backwards range is a 400 on from, never corrected.
     */
    @Transactional(readOnly = true)
    public RequestHistoryPageResponse history(
        int page, int size, String status, LocalDate from, LocalDate to, String bloodTypeLabel, UUID hospitalId,
        AuthenticatedUser principal
    ) {
        Scope scope = scopeFor(principal);
        if (!scope.isBank() && hospitalId != null) {
            // Refused rather than ignored: a hospital only ever sees its own requests (Business Rule 9).
            throw new InvalidFieldException("hospitalId", "Only blood bank staff can filter by hospital.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidFieldException("from", "from must be on or before to.");
        }
        // Propagates IllegalArgumentException for an unrecognised label -> 400, like every blood-group parameter.
        BloodType bloodType = bloodTypeLabel == null ? null : BloodType.fromLabel(bloodTypeLabel);
        Set<RequestStatus> statuses = status == null ? FINISHED : EnumSet.of(RequestStatus.valueOf(status));

        List<Specification<BloodRequest>> conditions = new ArrayList<>();
        conditions.add((root, query, cb) -> root.get("status").in(statuses));
        conditions.add(scope.isBank() ? touchedByBank(scope.bloodBankId())
            : (root, query, cb) -> cb.equal(root.get("hospital").get("id"), scope.hospitalId()));
        if (bloodType != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("bloodType"), bloodType));
        }
        if (hospitalId != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("hospital").get("id"), hospitalId));
        }
        if (from != null) {
            Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
            conditions.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("closedAt"), start));
        }
        if (to != null) {
            Instant endExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            conditions.add((root, query, cb) -> cb.lessThan(root.get("closedAt"), endExclusive));
        }

        Page<BloodRequest> result = bloodRequestRepository.findAll(
            Specification.allOf(conditions), PageRequest.of(page, size, NEWEST_CLOSED_FIRST));

        return new RequestHistoryPageResponse(
            entries(result.getContent(), scope),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Who may read history and over what: active hospital staff (own hospital) and bank staff (own bank) assigned
     * to one. Everyone else is a 403; a suspended account never gets this far (the filter refuses its token).
     */
    private Scope scopeFor(AuthenticatedUser principal) {
        User user = userRepository.findById(principal.getId())
            .orElseThrow(() -> new NotFoundException("Account not found."));
        return switch (user.getRole()) {
            case HOSPITAL_STAFF -> {
                if (user.getHospital() == null) {
                    throw new AccessDeniedException("This account is not assigned to a hospital.");
                }
                yield new Scope(user.getHospital().getId(), null);
            }
            case BLOOD_BANK_STAFF -> {
                if (user.getBloodBank() == null) {
                    throw new AccessDeniedException("This account is not assigned to a blood bank.");
                }
                yield new Scope(null, user.getBloodBank().getId());
            }
            case ADMIN, DONOR -> throw new AccessDeniedException(
                "Request history is for hospital and blood bank staff.");
        };
    }

    /** A bank's history: requests it contributed units to, or declined (Business Rule 36). */
    private static Specification<BloodRequest> touchedByBank(UUID bloodBankId) {
        return (root, query, cb) -> {
            Subquery<Integer> contributed = query.subquery(Integer.class);
            var contribution = contributed.from(BloodRequestContribution.class);
            contributed.select(cb.literal(1)).where(
                cb.equal(contribution.get("bloodRequest"), root),
                cb.equal(contribution.get("bloodBank").get("id"), bloodBankId));

            Subquery<Integer> declined = query.subquery(Integer.class);
            var decline = declined.from(BloodRequestDecline.class);
            declined.select(cb.literal(1)).where(
                cb.equal(decline.get("bloodRequest"), root),
                cb.equal(decline.get("bloodBank").get("id"), bloodBankId));

            return cb.or(cb.exists(contributed), cb.exists(declined));
        };
    }

    /**
     * The page's rows with their contributions, in one query for the page; banks get the bank shape (no cancellation
     * note or name, Business Rule 35) and what they themselves did.
     */
    private List<RequestHistoryEntryResponse> entries(List<BloodRequest> requests, Scope scope) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = requests.stream().map(BloodRequest::getId).toList();
        Map<UUID, List<BloodRequestContribution>> byRequest = contributionRepository.findForRequests(ids).stream()
            .collect(Collectors.groupingBy(contribution -> contribution.getBloodRequest().getId()));
        Set<UUID> declinedByUs = scope.isBank()
            ? new HashSet<>(declineRepository.findRequestIdsDeclinedByBank(scope.bloodBankId(), ids))
            : Set.of();

        return requests.stream().map(request -> {
            List<BloodRequestContribution> contributions = byRequest.getOrDefault(request.getId(), List.of());
            if (!scope.isBank()) {
                return new RequestHistoryEntryResponse(BloodRequestResponse.from(request, contributions), null, null);
            }
            int ours = contributions.stream()
                .filter(contribution -> contribution.getBloodBank().getId().equals(scope.bloodBankId()))
                .mapToInt(BloodRequestContribution::getUnitsContributed)
                .sum();
            return new RequestHistoryEntryResponse(
                BloodRequestResponse.forBank(request, contributions), ours, declinedByUs.contains(request.getId()));
        }).toList();
    }
}
