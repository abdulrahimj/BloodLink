package com.bloodlink.bloodbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.bloodbank.dto.DashboardResponse;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.DonationRepository;

/**
 * Covers the one thing easy to get backwards in getDashboard: which numbers are
 * scoped to this bank (units, donations, registered donors) and which are
 * deliberately citywide (pending requests, per Business Rule 6).
 */
@ExtendWith(MockitoExtension.class)
class BloodBankServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();

    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private DonationRepository donationRepository;
    @Mock private BloodRequestRepository bloodRequestRepository;
    @Mock private InventoryService inventoryService;

    private BloodBankService service;

    @BeforeEach
    void setUp() {
        service = new BloodBankService(bloodBankRepository, donationRepository, bloodRequestRepository, inventoryService);
    }

    @Test
    void dashboardCombinesBankScopedAndCitywideFigures() {
        BloodBank bank = new BloodBank();
        bank.setId(BANK_ID);
        bank.setName("Makeni Regional Blood Bank");
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank));

        when(inventoryService.listInventory(BANK_ID)).thenReturn(List.of(
            new InventoryItemResponse("O+", 89, null, null, StockStatus.SUFFICIENT)
        ));

        when(donationRepository.countByBloodBankIdAndDonationDateBetween(any(), any(), any())).thenReturn(12L);
        when(donationRepository.countDistinctDonorsByBloodBankId(BANK_ID)).thenReturn(512L);
        when(bloodRequestRepository.countByStatus(RequestStatus.PENDING)).thenReturn(2L);

        Hospital hospital = new Hospital();
        hospital.setName("Makeni Regional Hospital");
        BloodRequest request = new BloodRequest();
        request.setHospital(hospital);
        request.setBloodType(BloodType.O_NEGATIVE);
        request.setUnitsRequested(2);
        request.setUrgencyLevel(UrgencyLevel.EMERGENCY);
        request.setStatus(RequestStatus.PENDING);
        request.setCreatedAt(java.time.Instant.now());
        when(bloodRequestRepository.findTop5ByOrderByCreatedAtDesc()).thenReturn(List.of(request));

        DashboardResponse dashboard = service.getDashboard(BANK_ID);

        assertThat(dashboard.bloodBankName()).isEqualTo("Makeni Regional Blood Bank");
        assertThat(dashboard.totalUnits()).isEqualTo(89); // this bank's own stock only
        assertThat(dashboard.donationsThisMonth()).isEqualTo(12);
        assertThat(dashboard.registeredDonorsCount()).isEqualTo(512);
        assertThat(dashboard.pendingRequestsCount()).isEqualTo(2); // citywide, not bank-scoped
        assertThat(dashboard.recentRequests()).hasSize(1);
        assertThat(dashboard.recentRequests().get(0).hospitalName()).isEqualTo("Makeni Regional Hospital");
    }

    @Test
    void listAllReturnsSeededBanksInNameOrder() {
        BloodBank holySpirit = new BloodBank();
        holySpirit.setId(UUID.randomUUID());
        holySpirit.setName("Holy Spirit Blood Bank");
        holySpirit.setLocation("Makeni City");
        holySpirit.setContactPhone("+232 76 100 002");
        when(bloodBankRepository.findAllByOrderByNameAsc()).thenReturn(List.of(holySpirit));

        var banks = service.listAll();

        assertThat(banks).hasSize(1);
        assertThat(banks.get(0).name()).isEqualTo("Holy Spirit Blood Bank");
    }
}
