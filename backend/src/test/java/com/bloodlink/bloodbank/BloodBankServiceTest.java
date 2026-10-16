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
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.DonationRepository;

/**
 * Covers the one thing easy to get backwards in getDashboard: every figure is
 * scoped to this bank, including the pending-request count, which is counted over
 * the same "unclaimed, or already ours" scope the requests panel lists (Business
 * Rule 6) rather than citywide.
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
    void dashboardScopesEveryFigureToThisBank() {
        BloodBank bank = new BloodBank();
        bank.setId(BANK_ID);
        bank.setName("Makeni Regional Blood Bank");
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank));

        when(inventoryService.listInventory(BANK_ID)).thenReturn(List.of(
            // 89 usable units, plus 7 more in expired batches that are not stock
            // (Business Rule 24) and must not reach the dashboard total.
            new InventoryItemResponse("O+", 89, null, null, StockStatus.SUFFICIENT, AppealAvailability.NO_SHORTAGE, 0, 7, 2)
        ));

        when(donationRepository.countByBloodBankIdAndDonationDateBetween(any(), any(), any())).thenReturn(12L);
        when(donationRepository.countDistinctDonorsByBloodBankId(BANK_ID)).thenReturn(512L);
        when(bloodRequestRepository.countOpenForBank(BANK_ID)).thenReturn(2L);

        DashboardResponse dashboard = service.getDashboard(BANK_ID);

        assertThat(dashboard.bloodBankName()).isEqualTo("Makeni Regional Blood Bank");
        assertThat(dashboard.totalUnits()).isEqualTo(89);
        assertThat(dashboard.donationsThisMonth()).isEqualTo(12);
        assertThat(dashboard.registeredDonorsCount()).isEqualTo(512);
        // Open-for-this-bank, matching the panel below the card — not every
        // unfilled request in the city (Business Rule 20).
        assertThat(dashboard.openRequestsCount()).isEqualTo(2);
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
