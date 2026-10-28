package com.bloodlink.donor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.donor.dto.CorrectBloodGroupPayload;
import com.bloodlink.donor.dto.DonorBloodGroupResponse;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Business Rule 28's manual correction: staff set or change a donor's group with a note, audited;
 * the same group is a 400, and a caller the stock-change rule refuses reads and writes nothing.
 */
@ExtendWith(MockitoExtension.class)
class DonorBloodGroupServiceTest {

    private static final UUID DONOR_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();

    @Mock private DonorRepository donorRepository;
    @Mock private ActingBankResolver actingBankResolver;
    @Mock private AuditLogService auditLogService;

    private DonorBloodGroupService service;

    @BeforeEach
    void setUp() {
        service = new DonorBloodGroupService(donorRepository, actingBankResolver, auditLogService);
    }

    private static Donor donor(BloodType group) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Fatmata Kamara");
        Donor donor = new Donor();
        donor.setId(DONOR_ID);
        donor.setUser(user);
        donor.setBloodType(group);
        return donor;
    }

    private void stubStaffAndDonor(Donor donor) {
        BloodBank bank = new BloodBank();
        bank.setName("Holy Spirit Blood Bank");
        when(actingBankResolver.resolveForStockChange(any(), any())).thenReturn(bank);
        when(donorRepository.findByIdForUpdate(DONOR_ID)).thenReturn(Optional.of(donor));
    }

    private static AuthenticatedUser staff() {
        return new AuthenticatedUser(STAFF_ID, "+23276000003", "hash", Role.BLOOD_BANK_STAFF);
    }

    @Test
    void staffCorrectAGroupAndTheAuditEntryCarriesTheNote() {
        Donor donor = donor(BloodType.A_POSITIVE);
        stubStaffAndDonor(donor);

        DonorBloodGroupResponse response = service.correct(
            DONOR_ID, new CorrectBloodGroupPayload("B+", "  Retested at Holy Spirit lab, sample 4471  "), staff());

        assertThat(donor.getBloodType()).isEqualTo(BloodType.B_POSITIVE);
        assertThat(response.previousBloodType()).isEqualTo("A+");
        assertThat(response.bloodType()).isEqualTo("B+");
        verify(donorRepository).save(donor);
        verify(auditLogService).record(eq(STAFF_ID), eq(AuditAction.DONOR_BLOOD_GROUP_UPDATED), eq(donor.getUser()),
            eq("Blood group changed from A+ to B+. Source: manual correction by staff of Holy Spirit Blood Bank."
                + " Note: Retested at Holy Spirit lab, sample 4471"));
    }

    @Test
    void staffSetAGroupForADonorWhoHadNone() {
        Donor donor = donor(null);
        stubStaffAndDonor(donor);

        DonorBloodGroupResponse response = service.correct(
            DONOR_ID, new CorrectBloodGroupPayload("O-", "Typed at the screening clinic"), staff());

        assertThat(donor.getBloodType()).isEqualTo(BloodType.O_NEGATIVE);
        assertThat(response.previousBloodType()).isNull();
        verify(auditLogService).record(eq(STAFF_ID), eq(AuditAction.DONOR_BLOOD_GROUP_UPDATED), eq(donor.getUser()),
            eq("Blood group set to O- (none was recorded before). Source: manual correction by staff of"
                + " Holy Spirit Blood Bank. Note: Typed at the screening clinic"));
    }

    @Test
    void theSameGroupIsRefusedWithA400AndNothingIsWritten() {
        Donor donor = donor(BloodType.AB_NEGATIVE);
        stubStaffAndDonor(donor);

        assertThatThrownBy(() -> service.correct(
                DONOR_ID, new CorrectBloodGroupPayload("AB-", "Retested"), staff()))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessage("Fatmata Kamara's blood group is already AB-.")
            .extracting(e -> ((InvalidFieldException) e).getField()).isEqualTo("bloodType");

        verify(donorRepository, never()).save(any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void aCallerTheStockChangeRuleRefusesReadsAndWritesNothing() {
        when(actingBankResolver.resolveForStockChange(any(), eq("correct a donor's blood group")))
            .thenThrow(new AccessDeniedException("Only the blood bank's own staff can correct a donor's blood group."));
        AuthenticatedUser admin = new AuthenticatedUser(STAFF_ID, "+23276000009", "hash", Role.ADMIN);

        assertThatThrownBy(() -> service.correct(DONOR_ID, new CorrectBloodGroupPayload("B+", "Retested"), admin))
            .isInstanceOf(AccessDeniedException.class);

        verify(donorRepository, never()).findByIdForUpdate(any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void anUnknownDonorIsA404() {
        when(actingBankResolver.resolveForStockChange(any(), any())).thenReturn(new BloodBank());
        when(donorRepository.findByIdForUpdate(DONOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.correct(DONOR_ID, new CorrectBloodGroupPayload("B+", "Retested"), staff()))
            .isInstanceOf(NotFoundException.class);
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void theDonationCheckPassesAnEmptyProfileTheSameGroupOrAConfirmedChange() {
        service.requireConfirmedIfChanging(donor(null), BloodType.B_POSITIVE, false);
        service.requireConfirmedIfChanging(donor(BloodType.B_POSITIVE), BloodType.B_POSITIVE, false);
        service.requireConfirmedIfChanging(donor(BloodType.A_POSITIVE), BloodType.B_POSITIVE, true);
    }
}
