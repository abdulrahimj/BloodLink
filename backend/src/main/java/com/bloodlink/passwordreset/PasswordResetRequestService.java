package com.bloodlink.passwordreset;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.domain.PasswordResetRequest;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.PasswordResetRequestStatus;
import com.bloodlink.domain.enums.PasswordResetResolution;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.passwordreset.dto.PasswordResetRequestPageResponse;
import com.bloodlink.passwordreset.dto.PasswordResetRequestResponse;
import com.bloodlink.repository.PasswordResetRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.NotFoundException;

/**
 * "Forgot password?" requests (Business Rule 33): recorded and announced to administrators, who call back and reset
 * the password themselves (Business Rule 31). Nothing here ever changes a password.
 */
@Service
public class PasswordResetRequestService {

    /** A second ask sooner than this after the last one changes nothing and alerts nobody. */
    static final Duration REALERT_AFTER = Duration.ofHours(1);

    private final PasswordResetRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    PasswordResetRequestService(
        PasswordResetRequestRepository requestRepository,
        UserRepository userRepository,
        NotificationService notificationService,
        AuditLogService auditLogService,
        Clock clock
    ) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /** What happened to one ask; never told to the caller, who always gets the same answer. */
    public enum Outcome { NO_ACCOUNT, SUSPENDED, ALREADY_WAITING, REALERTED, CREATED }

    /**
     * Records an ask for an already-normalised phone. Unknown or suspended: nothing. Active: a new waiting request,
     * or the waiting one re-dated, with admins alerted, unless it was asked for less than an hour ago.
     */
    @Transactional
    public Outcome request(String normalisedPhone) {
        User found = userRepository.findByPhone(normalisedPhone).orElse(null);
        if (found == null) {
            return Outcome.NO_ACCOUNT;
        }
        if (found.getStatus() != AccountStatus.ACTIVE) {
            return Outcome.SUSPENDED;
        }
        // The account row is locked so two asks at once take turns: never two waiting requests, never two alerts.
        User user = userRepository.lockById(found.getId()).orElseThrow();
        Instant now = clock.instant();

        Optional<PasswordResetRequest> waiting = requestRepository.lockWaitingForUser(user.getId());
        Outcome outcome;
        if (waiting.isPresent()) {
            PasswordResetRequest request = waiting.get();
            if (request.getRequestedAt().isAfter(now.minus(REALERT_AFTER))) {
                return Outcome.ALREADY_WAITING;
            }
            request.setRequestedAt(now);
            outcome = Outcome.REALERTED;
        } else {
            requestRepository.save(PasswordResetRequest.waiting(user, now));
            outcome = Outcome.CREATED;
        }
        notificationService.notifyPasswordResetRequested(user);
        return outcome;
    }

    /** One page of a status, newest first: waiting by when asked, done by when handled; id breaks ties. */
    @Transactional(readOnly = true)
    public PasswordResetRequestPageResponse list(PasswordResetRequestStatus status, int page, int size) {
        String newest = status == PasswordResetRequestStatus.WAITING ? "requestedAt" : "handledAt";
        Page<PasswordResetRequest> found = requestRepository.findPageByStatus(
            status, PageRequest.of(page, size, Sort.by(Sort.Order.desc(newest), Sort.Order.desc("id"))));
        return new PasswordResetRequestPageResponse(
            found.getContent().stream().map(PasswordResetRequestResponse::from).toList(),
            found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    /** How many are waiting, for the menu badge and the User Accounts banner. */
    @Transactional(readOnly = true)
    public long countWaiting() {
        return requestRepository.countByStatus(PasswordResetRequestStatus.WAITING);
    }

    /**
     * "Mark done": closes a waiting request without a reset, with an optional note (blank is none), audited as
     * PASSWORD_REQUEST_CLOSED. Already done is a 409, unknown a 404.
     */
    @Transactional
    public PasswordResetRequestResponse close(UUID requestId, String note, AuthenticatedUser principal) {
        PasswordResetRequest request = requestRepository.lockById(requestId)
            .orElseThrow(() -> new NotFoundException("No password reset request found for this id."));
        if (!request.isWaiting()) {
            throw new ConflictException("This request has already been handled.");
        }
        String trimmed = note == null || note.isBlank() ? null : note.trim();

        request.handle(PasswordResetResolution.CLOSED, userRepository.getReferenceById(principal.getId()),
            clock.instant(), trimmed);
        auditLogService.record(principal.getId(), AuditAction.PASSWORD_REQUEST_CLOSED, request.getUser(),
            "Password reset request closed without a reset." + (trimmed == null ? "" : " Note: " + trimmed));

        PasswordResetRequest saved = requestRepository.saveAndFlush(request);
        return PasswordResetRequestResponse.from(saved);
    }

    /**
     * Called by every administrator reset (Business Rule 31) in its transaction: closes the account's waiting request
     * as RESET by that administrator. True when there was one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean closeOnReset(User account, UUID administratorId, Instant at) {
        return requestRepository.lockWaitingForUser(account.getId())
            .map(request -> {
                request.handle(PasswordResetResolution.RESET, userRepository.getReferenceById(administratorId), at, null);
                return true;
            })
            .orElse(false);
    }
}
