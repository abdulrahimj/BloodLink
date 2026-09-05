package com.bloodlink.bloodbank;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.bloodbank.dto.BloodBankResponse;
import com.bloodlink.bloodbank.dto.DashboardResponse;
import com.bloodlink.bloodbank.dto.PendingRequestItem;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Blood bank reference data, and the aggregate view behind the Dashboard home
 * screen — a mix of this bank's own inventory total plus citywide donation and
 * request figures (see the repository methods this pulls from for what "this
 * bank's" versus "citywide" means for each number).
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

    /** The fixed reference list — seeded, never self-registering. */
    @Transactional(readOnly = true)
    public List<BloodBankResponse> listAll() {
        return bloodBankRepository.findAllByOrderByNameAsc().stream()
            .map(bank -> new BloodBankResponse(bank.getId(), bank.getName(), bank.getLocation(), bank.getContactPhone()))
            .toList();
    }

    /** Everything the Dashboard home view needs in one call. */
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
        long pendingRequests = bloodRequestRepository.countByStatus(RequestStatus.PENDING);

        List<PendingRequestItem> recentRequests = bloodRequestRepository.findTop5ByOrderByCreatedAtDesc().stream()
            .map(this::toRequestItem)
            .toList();

        return new DashboardResponse(
            bank.getId(), bank.getName(), totalUnits,
            (int) donationsThisMonth, (int) pendingRequests, (int) registeredDonors,
            recentRequests
        );
    }

    private PendingRequestItem toRequestItem(BloodRequest request) {
        return new PendingRequestItem(
            request.getId(),
            request.getHospital().getName(),
            request.getBloodType().getLabel(),
            request.getUnitsRequested(),
            request.getUrgencyLevel(),
            request.getStatus(),
            request.getCreatedAt()
        );
    }
}
