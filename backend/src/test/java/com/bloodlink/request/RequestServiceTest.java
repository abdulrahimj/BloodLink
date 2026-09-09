package com.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;

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
        request.setFulfilledByBloodBank(fulfiller);
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
        assertThat(created.fulfilledByBloodBankId()).isNull();
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

    // ---------- approving ----------

    @Test
    void approvingClaimsTheRequestForTheActingBankWithoutDeductingStock() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(stock(5)));
        echoSavedRequest();

        BloodRequestResponse approved = service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.APPROVE, null),
            principal(Role.BLOOD_BANK_STAFF)
        );

        assertThat(approved.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(approved.fulfilledByBloodBankId()).isEqualTo(BANK_ID);
        // Units move at fulfilment, not at approval.
        verify(bloodInventoryRepository, never()).save(any());
    }

    @Test
    void approvingIsRefusedWhenTheBankCannotCoverTheRequest() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(stock(1)));

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.APPROVE, null),
            principal(Role.BLOOD_BANK_STAFF)
        ))
            .isInstanceOf(ConflictException.class)
            // The operator is told how short they are, not just that it failed.
            .hasMessageContaining("1 unit available")
            .hasMessageContaining("2 requested");

        verify(bloodRequestRepository, never()).save(any());
    }

    @Test
    void anAlreadyApprovedRequestCannotBeApprovedAgain() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        // What the second of two banks sees once the lock releases: no longer PENDING.
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.APPROVED, bank(OTHER_BANK_ID, "Holy Spirit Blood Bank"))));

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.APPROVE, null),
            principal(Role.BLOOD_BANK_STAFF)
        )).isInstanceOf(ConflictException.class);
    }

    // ---------- rejecting ----------

    @Test
    void rejectingIsTerminalAndClaimsNothing() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        echoSavedRequest();

        BloodRequestResponse rejected = service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.REJECT, null),
            principal(Role.BLOOD_BANK_STAFF)
        );

        assertThat(rejected.status()).isEqualTo(RequestStatus.REJECTED);
        // "fulfilled by" means "is supplying this"; a rejection supplies nothing.
        assertThat(rejected.fulfilledByBloodBankId()).isNull();
    }

    // ---------- fulfilling ----------

    @Test
    void fulfillingDeductsTheRequestedUnitsFromTheFulfillingBank() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.APPROVED, actingBank)));
        BloodInventory row = stock(5);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(row));
        echoSavedRequest();

        BloodRequestResponse fulfilled = service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.FULFIL, null),
            principal(Role.BLOOD_BANK_STAFF)
        );

        assertThat(fulfilled.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(row.getUnitsAvailable()).isEqualTo(3); // 5 - 2
        verify(bloodInventoryRepository).save(row);
    }

    @Test
    void fulfillingIsRefusedWhenStockHasFallenBelowTheRequestSinceApproval() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.APPROVED, actingBank)));
        BloodInventory row = stock(1);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.FULFIL, null),
            principal(Role.BLOOD_BANK_STAFF)
        )).isInstanceOf(ConflictException.class);

        // Nothing partially applied: the units stay put and the status is unchanged.
        assertThat(row.getUnitsAvailable()).isEqualTo(1);
        verify(bloodInventoryRepository, never()).save(any());
        verify(bloodRequestRepository, never()).save(any());
    }

    @Test
    void fulfillingIsRefusedWhenThisBankHasNeverStockedThatGroup() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.APPROVED, actingBank)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodTypeForUpdate(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.FULFIL, null),
            principal(Role.BLOOD_BANK_STAFF)
        ))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("0 units available");
    }

    @Test
    void aPendingRequestCannotBeFulfilledWithoutBeingApprovedFirst() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.FULFIL, null),
            principal(Role.BLOOD_BANK_STAFF)
        )).isInstanceOf(ConflictException.class);

        verify(bloodInventoryRepository, never()).findByBloodBankIdAndBloodTypeForUpdate(any(), any());
    }

    @Test
    void aBankCannotFulfilARequestAnotherBankApproved() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(actingBank, null)));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.APPROVED, bank(OTHER_BANK_ID, "Holy Spirit Blood Bank"))));

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.FULFIL, null),
            principal(Role.BLOOD_BANK_STAFF)
        )).isInstanceOf(AccessDeniedException.class);

        verify(bloodInventoryRepository, never()).findByBloodBankIdAndBloodTypeForUpdate(any(), any());
    }

    // ---------- who may act ----------

    @Test
    void hospitalStaffCannotActOnTheirOwnRequest() {
        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.APPROVE, BANK_ID),
            principal(Role.HOSPITAL_STAFF)
        )).isInstanceOf(AccessDeniedException.class);

        verify(bloodRequestRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void bankStaffWithNoAssignedBankCannotAct() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff(null, null)));

        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            // Naming a bank in the body must not substitute for being assigned to one.
            new RequestActionPayload(RequestAction.APPROVE, BANK_ID),
            principal(Role.BLOOD_BANK_STAFF)
        )).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdministratorMustNameTheBankTheyAreActingFor() {
        assertThatThrownBy(() -> service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.APPROVE, null),
            principal(Role.ADMIN)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAdministratorCanApproveOnBehalfOfANamedBank() {
        BloodBank actingBank = bank(BANK_ID, "Makeni Regional Blood Bank");
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(actingBank));
        when(bloodRequestRepository.findByIdForUpdate(REQUEST_ID))
            .thenReturn(Optional.of(request(RequestStatus.PENDING, null)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(stock(9)));
        echoSavedRequest();

        BloodRequestResponse approved = service.act(
            REQUEST_ID,
            new RequestActionPayload(RequestAction.APPROVE, BANK_ID),
            principal(Role.ADMIN)
        );

        assertThat(approved.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(approved.fulfilledByBloodBankName()).isEqualTo("Makeni Regional Blood Bank");
    }
}
