package com.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.BloodRequestDecline;
import com.bloodlink.domain.ContributionAllocation;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CancelRequestPayload;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;

/**
 * The request lifecycle's edges and illegal transitions, stock leaving at contribution,
 * and the scoping that keeps hospitals to their own requests.
 */
@ExtendWith(MockitoExtension.class)
class RequestServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID OTHER_BANK_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);

    @Mock private BloodRequestRepository bloodRequestRepository;
    @Mock private BloodInventoryBatchRepository batchRepository;
    @Mock private ContributionAllocationRepository allocationRepository;
    @Mock private BloodRequestDeclineRepository declineRepository;
    @Mock private BloodRequestContributionRepository contributionRepository;
    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;

    private RequestService service;

    @BeforeEach
    void setUp() {
        // A real ActingBankResolver and BatchStockService over mocked repositories,
        // so the "who may act" and stock-deduction tests cover the real rules.
        service = new RequestService(
            bloodRequestRepository,
            new BatchStockService(batchRepository, allocationRepository, new StockPolicy(CLOCK)),
            declineRepository,
            contributionRepository,
            bloodBankRepository,
            userRepository,
            new ActingBankResolver(userRepository, bloodBankRepository),
            notificationService,
            auditLogService,
            CLOCK
        );
    }

    // ---------- fixtures ----------

    private static BloodBank bank(UUID id, String name) {
        BloodBank bank = new BloodBank();
        bank.setId(id);
        bank.setName(name);
        return bank;
    }

    private static Hospital hospital() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        hospital.setName("Makeni Regional Hospital");
        return hospital;
    }

    private static AuthenticatedUser principal(Role role) {
        return new AuthenticatedUser(USER_ID, "+23276000000", "hash", role);
    }

    /** A staff account with whichever facility link the role in question needs. */
    private User staff(BloodBank assignedBank, Hospital assignedHospital) {
        User user = new User();
        user.setId(USER_ID);
        user.setBloodBank(assignedBank);
        user.setHospital(assignedHospital);
        return user;
    }

    private static BloodRequest request(RequestStatus status, BloodBank fulfiller) {
        BloodRequest request = new BloodRequest();
        request.setId(REQUEST_ID);
        request.setHospital(hospital());
        request.setBloodType(BloodType.O_NEGATIVE);
        request.setUnitsRequested(2);
        request.setUrgencyLevel(UrgencyLevel.EMERGENCY);
        request.setStatus(status);
        request.setLastContributedByBloodBank(fulfiller);
        request.setCreatedAt(Instant.now());
        return request;
    }

    /** One usable O- batch on a bank's shelf, with an id, as a locked row would have. */
    private static BloodInventoryBatch stock(int units) {
        return batch(units, TODAY.plusDays(20));
    }

    private static BloodInventoryBatch batch(int units, LocalDate expiry) {
        BloodInventoryBatch batch = BloodInventoryBatch.manual(new BloodBank(), BloodType.O_NEGATIVE, units, expiry);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        return batch;
    }

    /** The bank's usable O- batches, as the FEFO lock query would return them. */
    private void shelfHolds(UUID bankId, BloodInventoryBatch... batches) {
        when(batchRepository.lockUsableBatchesInIssueOrder(bankId, BloodType.O_NEGATIVE, TODAY))
            .thenReturn(List.of(batches));
    }

    /** save() returns its argument, with an id as the real repository gives a new row. */
    private void echoSavedRequest() {
        when(bloodRequestRepository.save(any(BloodRequest.class)))
            .thenAnswer(invocation -> {
                BloodRequest saved = invocation.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId(REQUEST_ID);
                }
                return saved;
            });
    }

    // ---------- cancelling (Business Rule 35) ----------

    /** The filter stops a suspended account first; this is the service's own second check, before any lock. */
    @Test
    void suspendedHospitalStaffCannotCancelEvenIfTheyGetPastTheFilter() {
        User suspended = staff(null, hospital());
        suspended.setStatus(AccountStatus.SUSPENDED);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.cancel(
            REQUEST_ID, new CancelRequestPayload("NO_LONGER_NEEDED", null), principal(Role.HOSPITAL_STAFF)
        )).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(bloodRequestRepository, auditLogService);
    }

    /** Refused here as well as by SecurityConfig. */
    @Test
    void anAdministratorCannotCancelOnAHospitalsBehalf() {
        assertThatThrownBy(() -> service.cancel(
            REQUEST_ID, new CancelRequestPayload("NO_LONGER_NEEDED", null), principal(Role.ADMIN)
        )).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(bloodRequestRepository, auditLogService);
    }

    // ---------- submitting ----------

    @Test
    void createStampsTheCallersOwnHospitalAndStartsPending() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(null, hospital())));
        echoSavedRequest();

        BloodRequestResponse created = service.createRequest(
            new CreateRequestPayload("O-", 2, UrgencyLevel.EMERGENCY, "Maternity Ward — Bed 12"),
            principal(Role.HOSPITAL_STAFF)
        );

        assertThat(created.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(created.status()).isEqualTo(RequestStatus.PENDING);
        // No bank owns a request until one approves it (Business Rule 6).
        assertThat(created.lastContributedByBloodBankId()).isNull();
        assertThat(created.patientReference()).isEqualTo("Maternity Ward — Bed 12");
    }

    @Test
    void createStoresAnUntouchedWardFieldAsAbsentRatherThanEmpty() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(null, hospital())));
        echoSavedRequest();

        BloodRequestResponse created = service.createRequest(
            new CreateRequestPayload("O-", 1, UrgencyLevel.ROUTINE, "   "),
            principal(Role.HOSPITAL_STAFF)
        );

        assertThat(created.patientReference()).isNull();
    }

    @Test
    void bloodBankStaffCannotSubmitARequest() {
        assertThatThrownBy(() -> service.createRequest(
            new CreateRequestPayload("O-", 2, UrgencyLevel.URGENT, null),
            principal(Role.BLOOD_BANK_STAFF)
        )).isInstanceOf(AccessDeniedException.class);

        verify(bloodRequestRepository, never()).save(any());
    }

    @Test
    void hospitalStaffWithNoHospitalCannotSubmitARequest() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(null, null)));

        assertThatThrownBy(() -> service.createRequest(
            new CreateRequestPayload("O-", 2, UrgencyLevel.URGENT, null),
            principal(Role.HOSPITAL_STAFF)
        )).isInstanceOf(AccessDeniedException.class);
    }

    // ---------- scoping ----------

    @Test
    void aHospitalSeesOnlyItsOwnRequests() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(null, hospital())));
        when(bloodRequestRepository.findByHospitalIdOrderByCreatedAtDescIdDesc(HOSPITAL_ID))
            .thenReturn(List.of(request(RequestStatus.PENDING, null)));

        var requests = service.listRequests(principal(Role.HOSPITAL_STAFF), null);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).hospitalId()).isEqualTo(HOSPITAL_ID);
        // Scoped by the caller's own hospital, never by anything they could pass in.
        verify(bloodRequestRepository, never()).findAllByOrderByCreatedAtDescIdDesc();
    }

    @Test
    void assignedBankStaffAreScopedToTheirOwnBankEvenIfAnotherIsRequested() {
        when(userRepository.findById(USER_ID))
            .thenReturn(Optional.of(staff(bank(BANK_ID, "Makeni Regional Blood Bank"), null)));
        when(bloodRequestRepository.findActionableForBank(BANK_ID)).thenReturn(List.of());

        service.listRequests(principal(Role.BLOOD_BANK_STAFF), OTHER_BANK_ID);

        verify(bloodRequestRepository).findActionableForBank(BANK_ID);
        verify(bloodRequestRepository, never()).findActionableForBank(OTHER_BANK_ID);
    }


    // ---------- contributing (Business Rule 20) ----------

    /** The simple case, and the one that used to take two clicks: one bank has
     *  enough, gives the whole amount, and the request is done. */
    @Test
    void oneContributionCoveringTheWholeRequestFulfilsItAndDeductsTheUnits() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        BloodInventoryBatch row = stock(5);
        shelfHolds(BANK_ID, row);
        echoSavedRequest();

        BloodRequestResponse fulfilled = contributeAs(Role.BLOOD_BANK_STAFF, null, 2);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(fulfilled.lastContributedByBloodBankId()).isEqualTo(BANK_ID);
        assertThat(row.getUnitsRemaining()).isEqualTo(3); // 5 - 2
        // And the units are traced to the batch they came from (Business Rule 24).
        ContributionAllocation allocation = savedAllocation();
        assertThat(allocation.getBatch()).isSameAs(row);
        assertThat(allocation.getUnitsAllocated()).isEqualTo(2);

        // The contribution row carries the three facts the table exists for.
        BloodRequestContribution recorded = savedContribution();
        assertThat(recorded.getUnitsContributed()).isEqualTo(2);
        assertThat(recorded.getBloodBank().getId()).isEqualTo(BANK_ID);
        assertThat(recorded.getBloodRequest().getId()).isEqualTo(REQUEST_ID);

        // A completed request is announced to the hospital as a fulfilment, not as
        // partial progress.
        verify(notificationService, never()).notifyRequestPartlyFilled(any(), any(), anyInt(), anyInt());
        verify(notificationService).notifyRequestProgress(any(), eq(actingBank), eq(2), eq(2));
    }

    @Test
    void aContributionThatLeavesUnitsOutstandingMakesTheRequestPartiallyFulfilled() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        BloodRequest pending = request(RequestStatus.PENDING, null);
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
        BloodInventoryBatch row = stock(5);
        shelfHolds(BANK_ID, row);
        echoSavedRequest();

        BloodRequestResponse afterFirst = contributeAs(Role.BLOOD_BANK_STAFF, null, 1);

        assertThat(afterFirst.status()).isEqualTo(RequestStatus.PARTIALLY_FULFILLED);
        assertThat(row.getUnitsRemaining()).isEqualTo(4); // only what was given
        // The hospital is told the figures, and the other banks are told what is left.
        verify(notificationService).notifyRequestPartlyFilled(pending, actingBank, 1, 1);
        verify(notificationService).notifyRequestProgress(pending, actingBank, 1, 1);
    }

    /** Two banks, one request: the second contribution completes what the first
     *  started. This is the case the whole feature exists for. */
    @Test
    void aSecondBanksContributionCompletesARequestTheFirstOnlyPartlyCovered() {
        BloodBank secondBank = bank(OTHER_BANK_ID, "Holy Spirit Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(secondBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(
            Optional.of(request(RequestStatus.PARTIALLY_FULFILLED, bank(BANK_ID, "Makeni Regional Blood Bank"))));
        // 1 of the 2 units already given by the other bank.
        when(contributionRepository.sumUnitsForRequest(REQUEST_ID)).thenReturn(1);
        BloodInventoryBatch row = stock(4);
        shelfHolds(OTHER_BANK_ID, row);
        echoSavedRequest();

        BloodRequestResponse fulfilled = contributeAs(Role.BLOOD_BANK_STAFF, null, 1);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        // The pointer follows the most recent contributor, not the first.
        assertThat(fulfilled.lastContributedByBloodBankId()).isEqualTo(OTHER_BANK_ID);
        assertThat(row.getUnitsRemaining()).isEqualTo(3);
        assertThat(savedContribution().getUnitsContributed()).isEqualTo(1);
    }

    /**
     * Offering more units than still needed takes only what is outstanding: the race the lock makes safe.
     */
    @Test
    void aContributionIsCappedAtWhatTheRequestStillNeeds() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PARTIALLY_FULFILLED, null)));
        when(contributionRepository.sumUnitsForRequest(REQUEST_ID)).thenReturn(1); // 1 of 2 covered
        BloodInventoryBatch row = stock(9);
        shelfHolds(BANK_ID, row);
        echoSavedRequest();

        // Offers five; only one is still wanted.
        BloodRequestResponse fulfilled = contributeAs(Role.BLOOD_BANK_STAFF, null, 5);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(savedContribution().getUnitsContributed()).isEqualTo(1);
        // Only the capped amount leaves the shelf — never the five that were typed.
        assertThat(row.getUnitsRemaining()).isEqualTo(8);
    }

    @Test
    void contributingMoreUnitsThanTheBankHoldsIsRefusedRatherThanCapped() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        BloodInventoryBatch row = stock(1);
        shelfHolds(BANK_ID, row);

        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, null, 2))
            .isInstanceOf(ConflictException.class)
            // The operator is told how short they are, not just that it failed.
            .hasMessageContaining("1 unit available")
            .hasMessageContaining("2 being contributed");

        // Nothing partially applied: the units stay put and nothing is recorded.
        assertThat(row.getUnitsRemaining()).isEqualTo(1);
        verify(allocationRepository, never()).save(any());
        verify(contributionRepository, never()).save(any());
        verify(bloodRequestRepository, never()).save(any());
    }

    @Test
    void contributingIsRefusedWhenThisBankHasNeverStockedThatGroup() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        shelfHolds(BANK_ID);

        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, null, 1))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("0 units available");
    }

    @Test
    void aClosedRequestTakesNoMoreUnits() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        // What the second of two banks sees once the lock releases: already covered.
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(
            Optional.of(request(RequestStatus.FULFILLED, bank(OTHER_BANK_ID, "Holy Spirit Blood Bank"))));

        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, null, 1))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already fulfilled");

        verify(batchRepository, never()).lockUsableBatchesInIssueOrder(any(), any(), any());
        verify(contributionRepository, never()).save(any());
    }

    @Test
    void aContributionMustSayHowManyUnits() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));

        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, null, null))
            .isInstanceOf(InvalidFieldException.class);

        verify(contributionRepository, never()).save(any());
    }

    /** A bank that contributed may not then decline the same request (Business Rule 20). */
    @Test
    void aBankThatHasContributedCannotThenDeclineTheSameRequest() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PARTIALLY_FULFILLED, actingBank)));
        when(contributionRepository.existsByBloodRequestIdAndBloodBankId(REQUEST_ID, BANK_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.REJECT, null, null),
            principal(Role.BLOOD_BANK_STAFF)
        ))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already contributed");

        verify(declineRepository, never()).save(any());
    }

    /** And the mirror image: a decline is this bank's final answer, so it cannot
     *  then contribute to the same request. */
    @Test
    void aBankThatHasDeclinedCannotThenContribute() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        when(declineRepository.existsByBloodRequestIdAndBloodBankId(REQUEST_ID, BANK_ID)).thenReturn(true);

        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, null, 1))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already declined");

        verify(contributionRepository, never()).save(any());
        verify(batchRepository, never()).lockUsableBatchesInIssueOrder(any(), any(), any());
    }

    /**
     * A request that has received units never closes by decline, however many banks decline (Business Rule 20).
     */
    @Test
    void everyBankDecliningDoesNotCloseARequestThatHasAlreadyReceivedUnits() {
        BloodBank actingBank = bank(OTHER_BANK_ID, "Holy Spirit Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(
            Optional.of(request(RequestStatus.PARTIALLY_FULFILLED, bank(BANK_ID, "Makeni Regional Blood Bank"))));
        // Every bank that exists has now declined the remainder.
        when(declineRepository.countByBloodRequestId(REQUEST_ID)).thenReturn(2L);
        when(bloodBankRepository.count()).thenReturn(2L);
        echoSavedRequest();

        BloodRequestResponse afterDecline = service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.REJECT, null, null),
            principal(Role.BLOOD_BANK_STAFF)
        );

        assertThat(afterDecline.status()).isEqualTo(RequestStatus.PARTIALLY_FULFILLED);
        // And the hospital is not told anything closed, because nothing did.
        ArgumentCaptor<BloodRequest> notified = ArgumentCaptor.forClass(BloodRequest.class);
        verify(notificationService).notifyRequestStatusChange(notified.capture());
        assertThat(notified.getValue().getStatus()).isEqualTo(RequestStatus.PARTIALLY_FULFILLED);
    }

    // ---------- who may act ----------

    @Test
    void hospitalStaffCannotActOnTheirOwnRequest() {
        assertThatThrownBy(() -> contributeAs(Role.HOSPITAL_STAFF, BANK_ID, 1))
            .isInstanceOf(AccessDeniedException.class);

        verify(bloodRequestRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void bankStaffWithNoAssignedBankCannotAct() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(null, null)));

        // Naming a bank in the body must not substitute for being assigned to one.
        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, BANK_ID, 1))
            .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Stage 3b-2: an ADMIN may not contribute, bank named or not, refused before any lock or stock read.
     * Replaces earlier tests asserting the opposite.
     */
    @Test
    void anAdministratorCannotContributeWhetherOrNotABankIsNamed() {
        for (UUID named : new UUID[] { BANK_ID, null }) {
            assertThatThrownBy(() -> contributeAs(Role.ADMIN, named, 2))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only the blood bank's own staff can contribute units to a blood request.");
        }

        verifyNoInteractions(bloodBankRepository, userRepository, batchRepository);
        verify(bloodRequestRepository, never()).findByIdForUpdate(any());
        verify(contributionRepository, never()).save(any());
        verify(allocationRepository, never()).save(any());
    }

    /** What an ADMIN keeps: declining on a named bank's behalf moves no blood. */
    @Test
    void anAdministratorCanStillDeclineOnBehalfOfANamedBank() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID, "Makeni Regional Blood Bank")));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        when(declineRepository.countByBloodRequestId(REQUEST_ID)).thenReturn(1L);
        when(bloodBankRepository.count()).thenReturn(2L);
        echoSavedRequest();

        BloodRequestResponse afterDecline = service.act(
            REQUEST_ID, new RequestActionPayload(RequestAction.REJECT, BANK_ID, null), principal(Role.ADMIN));

        assertThat(afterDecline.status()).isEqualTo(RequestStatus.PENDING);
        verify(declineRepository).save(any());
    }

    // ---------- helpers for the contribution tests ----------

    /** Contributes {@code units} to REQUEST_ID as the given role. */
    private BloodRequestResponse contributeAs(Role role, UUID bankId, Integer units) {
        return service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.CONTRIBUTE, bankId, units),
            principal(role)
        );
    }

    /** The single contribution row written by the call under test. */
    private BloodRequestContribution savedContribution() {
        ArgumentCaptor<BloodRequestContribution> captor = ArgumentCaptor.forClass(BloodRequestContribution.class);
        verify(contributionRepository).save(captor.capture());
        return captor.getValue();
    }

    /** The single allocation row written by the call under test. */
    private ContributionAllocation savedAllocation() {
        return savedAllocations().get(0);
    }

    /** Every allocation row written by the call under test, in the order written. */
    private List<ContributionAllocation> savedAllocations() {
        ArgumentCaptor<ContributionAllocation> captor = ArgumentCaptor.forClass(ContributionAllocation.class);
        verify(allocationRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    // ---------- first expiry, first out (Business Rule 24) ----------

    /**
     * Stock in two batches: the one expiring sooner is emptied first, and the
     * contribution's allocations name both batches and add up to its units.
     */
    @Test
    void aContributionTakesTheEarliestExpiringBatchFirstAndRecordsEachBatchItDrewOn() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        BloodRequest pending = request(RequestStatus.PENDING, null);
        pending.setUnitsRequested(4);
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
        BloodInventoryBatch soon = batch(3, TODAY.plusDays(2));
        BloodInventoryBatch later = batch(5, TODAY.plusDays(40));
        // Passed in the lock query's order, which is earliest expiry first.
        shelfHolds(BANK_ID, soon, later);
        echoSavedRequest();

        BloodRequestResponse fulfilled = contributeAs(Role.BLOOD_BANK_STAFF, null, 4);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(soon.getUnitsRemaining()).isZero();     // all 3 taken
        assertThat(later.getUnitsRemaining()).isEqualTo(4); // only the 1 still needed

        List<ContributionAllocation> allocations = savedAllocations();
        assertThat(allocations).extracting(ContributionAllocation::getBatch).containsExactly(soon, later);
        assertThat(allocations).extracting(ContributionAllocation::getUnitsAllocated).containsExactly(3, 1);
        assertThat(allocations).allSatisfy(a -> assertThat(a.getContribution()).isSameAs(savedContribution()));
        assertThat(allocations.stream().mapToInt(ContributionAllocation::getUnitsAllocated).sum())
            .isEqualTo(savedContribution().getUnitsContributed());

        // The low-stock check is told USABLE stock either side: 8 before, 4 after.
        verify(notificationService).notifyStockCrossing(actingBank, BloodType.O_NEGATIVE, 8, 4);
    }

    /**
     * A short-stock refusal names usable, attempted and expired units (Business Rule 24).
     */
    @Test
    void aRefusalForShortStockAlsoSaysHowManyExpiredUnitsCouldNotBeCounted() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        shelfHolds(BANK_ID, stock(1));
        when(batchRepository.sumExpiredUnits(BANK_ID, BloodType.O_NEGATIVE, TODAY)).thenReturn(10L);

        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, null, 2))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Not enough usable O-")
            .hasMessageContaining("1 unit available")
            .hasMessageContaining("2 being contributed")
            .hasMessageContaining("10 expired units cannot be issued");

        verify(contributionRepository, never()).save(any());
        verify(allocationRepository, never()).save(any());
    }
}
