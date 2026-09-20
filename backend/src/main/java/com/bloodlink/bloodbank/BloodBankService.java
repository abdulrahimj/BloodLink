package com.bloodlink.bloodbank;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.bloodbank.dto.BloodBankResponse;
import com.bloodlink.bloodbank.dto.DashboardResponse;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Blood bank reference data, and the stat-card figures behind the Dashboard home
 * screen — this bank's own inventory and donation totals, plus the count of
 * requests it could act on (see the repository methods this pulls from for what
 * "this bank's" means in each case).
 */
@Service
public class BloodBankService {

    private final BloodBankRepository bloodBankRepository;
    private final DonationRepository donationRepository;
    private final BloodRequestRepository bloodRequestRepository;
    private final InventoryService inventoryService;

    BloodBankService(
        BloodBankRepository bloodBankRepository,
        DonationRepository donationRepository,
        BloodRequestRepository bloodRequestRepository,
        InventoryService inventoryService
    ) {
        this.bloodBankRepository = bloodBankRepository;
        this.donationRepository = donationRepository;
        this.bloodRequestRepository = bloodRequestRepository;
        this.inventoryService = inventoryService;
    }

    /** The reference list — seeded, never self-registering, added to only by an
     *  administrator (Business Rule 19). */
    @Transactional(readOnly = true)
    public List<BloodBankResponse> listAll() {
        return bloodBankRepository.findAllByOrderByNameAsc().stream()
            .map(bank -> new BloodBankResponse(bank.getId(), bank.getName(), bank.getLocation(), bank.getContactPhone()))
            .toList();
    }

    /** The Dashboard home view's four stat cards. */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID bloodBankId) {
        BloodBank bank = bloodBankRepository.findById(bloodBankId)
            .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));

        int totalUnits = inventoryService.listInventory(bloodBankId).stream()
            .mapToInt(item -> item.unitsAvailable())
            .sum();

        YearMonth thisMonth = YearMonth.now();
        long donationsThisMonth = donationRepository.countByBloodBankIdAndDonationDateBetween(
            bloodBankId, thisMonth.atDay(1), thisMonth.atEndOfMonth()
        );
        long registeredDonors = donationRepository.countDistinctDonorsByBloodBankId(bloodBankId);
        // Counted over the same scope the requests panel lists, so "2 Open Requests"
        // always matches the rows the operator can see and act on. Since Business
        // Rule 20 that includes partially fulfilled ones: units are still needed, and
        // this bank can still supply them.
        long openRequests = bloodRequestRepository.countOpenForBank(bloodBankId);

        return new DashboardResponse(
            bank.getId(), bank.getName(), totalUnits,
            (int) donationsThisMonth, (int) openRequests, (int) registeredDonors
        );
    }
}
