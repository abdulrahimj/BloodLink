package com.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.BloodRequestDecline;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;

/**
 * Covers the parts of Figure 3.5 that are easy to get wrong and expensive if they
 * are: Business Rule 5's lifecycle edges (and the illegal ones), the deduction at
 * fulfilment, the refusal when stock has moved since approval, and the scoping
 * that keeps one hospital from seeing another's requests.
 */
@ExtendWith(MockitoExtension.class)
class RequestServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID OTHER_BANK_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private BloodRequestRepository bloodRequestRepository;
    @Mock private BloodInventoryRepository bloodInventoryRepository;
    @Mock private BloodRequestDeclineRepository declineRepository;
    @Mock private BloodRequestContributionRepository contributionRepository;
    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    private RequestService service;

    @BeforeEach
    void setUp() {
        // A real ActingBankResolver over the mocked repositories, not a mock of it.
        // "Which bank is this caller acting as" moved out of RequestService when
        // donation appeals needed the same rule, and stubbing it here would quietly
        // delete the coverage the "who may act" tests below already give it.
        service = new RequestService(
            bloodRequestRepository,
            bloodInventoryRepository,
            declineRepository,
            contributionRepository,
            bloodBankRepository,
            userRepository,
            new ActingBankResolver(userRepository, bloodBankRepository),
            notificationService
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

    private static BloodInventory stock(int units) {
        BloodInventory row = new BloodInventory();
        row.setBloodType(BloodType.O_NEGATIVE);
        row.setUnitsAvailable(units);
        return row;
    }

    /** save() returns its argument, as the real repository does for an update. */
    private void echoSavedRequest() {
        when(bloodRequestRepository.save(any(BloodRequest.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
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
        when(bloodRequestRepository.findByHospitalIdOrderByCreatedAtDesc(HOSPITAL_ID))
            .thenReturn(List.of(request(RequestStatus.PENDING, null)));

        var requests = service.listRequests(principal(Role.HOSPITAL_STAFF), null);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).hospitalId()).isEqualTo(HOSPITAL_ID);
        // Scoped by the caller's own hospital, never by anything they could pass in.
        verify(bloodRequestRepository, never()).findAllByOrderByCreatedAtDesc();
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
        BloodInventory row = stock(5);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(row));
        echoSavedRequest();

        BloodRequestResponse fulfilled = contributeAs(Role.BLOOD_BANK_STAFF, null, 2);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(fulfilled.lastContributedByBloodBankId()).isEqualTo(BANK_ID);
        assertThat(row.getUnitsAvailable()).isEqualTo(3); // 5 - 2
        verify(bloodInventoryRepository).save(row);

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
        BloodInventory row = stock(5);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(row));
        echoSavedRequest();

        BloodRequestResponse afterFirst = contributeAs(Role.BLOOD_BANK_STAFF, null, 1);

        assertThat(afterFirst.status()).isEqualTo(RequestStatus.PARTIALLY_FULFILLED);
        assertThat(row.getUnitsAvailable()).isEqualTo(4); // only what was given
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
        BloodInventory row = stock(4);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(OTHER_BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(row));
        echoSavedRequest();

        BloodRequestResponse fulfilled = contributeAs(Role.BLOOD_BANK_STAFF, null, 1);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        // The pointer follows the most recent contributor, not the first.
        assertThat(fulfilled.lastContributedByBloodBankId()).isEqualTo(OTHER_BANK_ID);
        assertThat(row.getUnitsAvailable()).isEqualTo(3);
        assertThat(savedContribution().getUnitsContributed()).isEqualTo(1);
    }

    /**
     * The capping rule, and the shape of the race the pessimistic lock turns into a
     * safe outcome: an operator offers more units than the request still needs
     * (because another bank contributed since their screen was drawn), and only the
     * units genuinely outstanding are taken.
     */
    @Test
    void aContributionIsCappedAtWhatTheRequestStillNeeds() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PARTIALLY_FULFILLED, null)));
        when(contributionRepository.sumUnitsForRequest(REQUEST_ID)).thenReturn(1); // 1 of 2 covered
        BloodInventory row = stock(9);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(row));
        echoSavedRequest();

        // Offers five; only one is still wanted.
        BloodRequestResponse fulfilled = contributeAs(Role.BLOOD_BANK_STAFF, null, 5);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(savedContribution().getUnitsContributed()).isEqualTo(1);
        // Only the capped amount leaves the shelf — never the five that were typed.
        assertThat(row.getUnitsAvailable()).isEqualTo(8);
    }

    @Test
    void contributingMoreUnitsThanTheBankHoldsIsRefusedRatherThanCapped() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        BloodInventory row = stock(1);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> contributeAs(Role.BLOOD_BANK_STAFF, null, 2))
            .isInstanceOf(ConflictException.class)
            // The operator is told how short they are, not just that it failed.
            .hasMessageContaining("1 unit available")
            .hasMessageContaining("2 being contributed");

        // Nothing partially applied: the units stay put and nothing is recorded.
        assertThat(row.getUnitsAvailable()).isEqualTo(1);
        verify(bloodInventoryRepository, never()).save(any());
        verify(contributionRepository, never()).save(any());
        verify(bloodRequestRepository, never()).save(any());
    }

    @Test
    void contributingIsRefusedWhenThisBankHasNeverStockedThatGroup() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.empty());

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

        verify(bloodInventoryRepository, never()).findByBloodBankIdAndBloodTypeForUpdate(any(), any());
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

    /** Business Rule 20: contributing and declining are opposite answers to the same
     *  question, so a bank that has given units may not then decline — a decline
     *  counts towards closing a request this bank has just helped fill. */
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
        verify(bloodInventoryRepository, never()).findByBloodBankIdAndBloodTypeForUpdate(any(), any());
    }

    /**
     * Business Rule 20's deliberate difference from Business Rule 11: a request that
     * has already received units never closes by decline, however many banks turn
     * down the remainder. Real progress exists on it, and stock that could complete
     * it may arrive tomorrow.
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

    @Test
    void anAdministratorMustNameTheBankTheyAreActingFor() {
        assertThatThrownBy(() -> contributeAs(Role.ADMIN, null, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAdministratorCanContributeOnBehalfOfANamedBank() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(actingBank));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(stock(9)));
        echoSavedRequest();

        BloodRequestResponse fulfilled = contributeAs(Role.ADMIN, BANK_ID, 2);

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(fulfilled.lastContributedByBloodBankName()).isEqualTo("Makeni Regional Blood Bank");
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
}
