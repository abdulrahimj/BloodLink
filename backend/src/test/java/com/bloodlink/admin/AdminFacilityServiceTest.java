package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.bloodlink.admin.dto.CreateFacilityPayload;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.bloodbank.dto.BloodBankResponse;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.hospital.dto.HospitalResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;

/**
 * Business Rule 19's service logic in isolation: names are normalised before they
 * are compared or stored, a duplicate is refused before anything is written or
 * logged, a lost race on the unique index still reads as a duplicate, and a real
 * creation is logged against the acting administrator. The database's own half
 * (V14's index and constraints) is proven in AdminFacilityCreationIT.
 */
@ExtendWith(MockitoExtension.class)
class AdminFacilityServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private AuditLogService auditLogService;

    private AdminFacilityService service;

    @BeforeEach
    void setUp() {
        service = new AdminFacilityService(bloodBankRepository, hospitalRepository, auditLogService);
    }

    private static AuthenticatedUser admin() {
        return new AuthenticatedUser(ADMIN_ID, "+23276000099", "hash", Role.ADMIN);
    }

    @Test
    void namesAreTrimmedAndInnerSpacesCollapsed() {
        assertThat(AdminFacilityService.normaliseName("  Holy   Spirit\tBlood Bank  "))
            .isEqualTo("Holy Spirit Blood Bank");
    }

    @Test
    void aBloodBankIsStoredNormalisedAndLoggedAgainstTheAdministrator() {
        when(bloodBankRepository.existsByNameIgnoreCase("Kabala Road Blood Bank")).thenReturn(false);
        when(bloodBankRepository.saveAndFlush(any(BloodBank.class))).thenAnswer(call -> {
            BloodBank bank = call.getArgument(0);
            bank.setId(UUID.randomUUID());
            return bank;
        });

        BloodBankResponse created = service.createBloodBank(
            new CreateFacilityPayload(" Kabala  Road Blood Bank ", " Kabala Road ", " 076 111 222 "), admin());

        assertThat(created.name()).isEqualTo("Kabala Road Blood Bank");
        assertThat(created.location()).isEqualTo("Kabala Road");
        assertThat(created.contactPhone()).isEqualTo("076 111 222");
        verify(auditLogService).recordBloodBankCreated(eq(ADMIN_ID), any(BloodBank.class),
            contains("Created blood bank Kabala Road Blood Bank"));
    }

    @Test
    void aDuplicateBloodBankIsRefusedBeforeAnythingIsWrittenOrLogged() {
        when(bloodBankRepository.existsByNameIgnoreCase("Holy Spirit Blood Bank")).thenReturn(true);

        assertThatThrownBy(() -> service.createBloodBank(
                new CreateFacilityPayload("Holy Spirit Blood Bank", "Makeni", "076"), admin()))
            .isInstanceOf(ConflictException.class)
            .hasMessage("A blood bank named \"Holy Spirit Blood Bank\" already exists.");

        verify(bloodBankRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void losingARaceOnTheUniqueIndexIsStillReportedAsADuplicateName() {
        when(hospitalRepository.existsByNameIgnoreCase("Teko Hospital")).thenReturn(false);
        when(hospitalRepository.saveAndFlush(any(Hospital.class)))
            .thenThrow(new DataIntegrityViolationException("uq_hospitals_name"));

        assertThatThrownBy(() -> service.createHospital(
                new CreateFacilityPayload("Teko Hospital", "Makeni", "076"), admin()))
            .isInstanceOf(ConflictException.class)
            .hasMessage("A hospital named \"Teko Hospital\" already exists.");

        verifyNoInteractions(auditLogService);
    }

    @Test
    void aHospitalIsLoggedAsAHospitalCreation() {
        when(hospitalRepository.existsByNameIgnoreCase("Teko Hospital")).thenReturn(false);
        when(hospitalRepository.saveAndFlush(any(Hospital.class))).thenAnswer(call -> call.getArgument(0));

        HospitalResponse created = service.createHospital(
            new CreateFacilityPayload("Teko Hospital", "Teko Barracks", "076 333 444"), admin());

        assertThat(created.name()).isEqualTo("Teko Hospital");
        verify(auditLogService).recordHospitalCreated(eq(ADMIN_ID), any(Hospital.class),
            eq("Created hospital Teko Hospital, location Teko Barracks, contact phone 076 333 444."));
    }
}
