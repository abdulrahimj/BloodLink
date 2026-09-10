package com.bloodlink.donation;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.donation.dto.DonationRecordedResponse;
import com.bloodlink.donation.dto.RecordDonationPayload;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * Recording a donation: the moment blood actually enters this system.
 *
 * Until now every Donation row had to be written by hand — the donor's history,
 * the Blood Bank Dashboard's monthly figure and the Admin Console's trend chart
 * all read a table nothing in the application could fill. This is the write side
 * those reads were waiting for.
 *
 * One action, three effects, one transaction:
 *   1. a Donation row, recording what was actually drawn and tested;
 *   2. the donor's last_donation_date, which is what Business Rule 2 measures
 *      their next eligibility from;
 *   3. the units added to this bank's stock of that group.
 *
 * They are inseparable, and the transaction is what makes that true. Blood on a
 * shelf that no donation accounts for, a donor who can be invited back tomorrow
 * because step 2 was skipped, or a donation on record whose units never reached
 * the shelf — each is a worse state than the whole thing failing and being
 * entered again.
 *
 * Business Rule 7 is not reimplemented here. Step 3 calls
 * {@link InventoryService#addBatch}, which already owns the accumulation and the
 * keep-the-earlier-expiry rule; a second copy of that arithmetic is exactly how
 * the two paths into inventory would eventually start disagreeing.
 */
@Service
public class DonationService {

    /**
     * How long the units from a fresh donation are treated as good for.
     *
     * 42 days is the standard shelf life of red cells in an additive solution,
     * which is what a whole-blood donation becomes. It is a default, not a
     * measurement: BloodInventory holds one expiry per (bank, group), so a
     * donation cannot carry the real date off its own bag label. Flagged for the
     * project owner — if Makeni's labs use a different preparation this number
     * should follow theirs, and this is the only place it is written down.
     */
    static final int SHELF_LIFE_DAYS = 42;

    private final DonationRepository donationRepository;
    private final DonorRepository donorRepository;
    private final ActingBankResolver actingBankResolver;
    private final InventoryService inventoryService;

    DonationService(
        DonationRepository donationRepository,
        DonorRepository donorRepository,
        ActingBankResolver actingBankResolver,
        InventoryService inventoryService
    ) {
        this.donationRepository = donationRepository;
        this.donorRepository = donorRepository;
        this.actingBankResolver = actingBankResolver;
        this.inventoryService = inventoryService;
    }

    /**
     * Records one donation and everything that follows from it.
     *
     * Eligibility is deliberately not enforced. Business Rule 2 governs whether a
     * donor should be *invited* to give — which is why the appeal filter refuses
     * to prompt someone inside their recovery window — but this method documents
     * a draw that has already happened. Refusing it would not un-draw the blood;
     * it would leave those units unaccounted for on the shelf and the donor's
     * record still showing they last gave months ago, which is the more dangerous
     * of the two errors. The screening decision belongs with the staff member
     * before the needle, and the Record Donation screen puts the donor's
     * eligibility in front of them so they make it with the facts in view.
     */
    @Transactional
    public DonationRecordedResponse recordDonation(RecordDonationPayload payload, AuthenticatedUser principal) {
        BloodBank bank = actingBankResolver.resolve(payload.bloodBankId(), principal, "record a donation");
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType testedBloodType = BloodType.fromLabel(payload.bloodType().trim());

        Donor donor = donorRepository.findById(payload.donorId())
            .orElseThrow(() -> new NotFoundException("No donor found for this id."));

        LocalDate today = LocalDate.now();

        // 1. What was drawn and tested. Business Rule 4: the group stored here is
        //    the one the staff member entered from today's test — donor.getBloodType()
        //    is never read in this method, so a stale profile value cannot leak in.
        Donation donation = new Donation();
        donation.setDonor(donor);
        donation.setBloodBank(bank);
        donation.setDonationDate(today);
        donation.setUnitsDonated(payload.unitsDonated());
        donation.setBloodType(testedBloodType);
        Donation saved = donationRepository.save(donation);

        // 2. The clock Business Rule 2 measures from. Without this the donor stays
        //    "eligible" and could be appealed to again the same week.
        donor.setLastDonationDate(today);
        donorRepository.save(donor);

        // 3. The units, into this bank's stock of the tested group. addBatch owns
        //    Business Rule 7 — units add together, expiry keeps the earlier of the
        //    two — and raises the low-stock check on the way through, which cannot
        //    fire on an increase but stays correct if that ever changes.
        InventoryItemResponse stock = inventoryService.addBatch(
            bank.getId(),
            testedBloodType.getLabel(),
            new AddBatchRequest(payload.unitsDonated(), today.plusDays(SHELF_LIFE_DAYS)),
            principal
        );

        return new DonationRecordedResponse(
            saved.getId(),
            donor.getId(),
            donor.getUser().getFullName(),
            testedBloodType.getLabel(),
            saved.getUnitsDonated(),
            today,
            bank.getId(),
            bank.getName(),
            stock.unitsAvailable(),
            stock.expiryDate(),
            stock.status()
        );
    }
}
