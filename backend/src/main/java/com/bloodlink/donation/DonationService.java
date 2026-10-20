package com.bloodlink.donation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Recording a donation, in one transaction: the Donation row, the donor's last_donation_date, and a new batch
 * of stock (Business Rule 24). Expiry is as entered, or donation date + 42 days (provisional).
 */
@Service
public class DonationService {

    /** "10 December 2026" — the same long form the frontend's formatFullDate
     *  uses, so the refusal reads like the rest of the app's dates. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

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
     * Records one donation for an eligible donor only: one in recovery is a 409 with nothing written (Business Rule 2).
     * The donor row is write-locked before the check, so two simultaneous submissions cannot both pass.
     */
    @Transactional
    public DonationRecordedResponse recordDonation(RecordDonationPayload payload, AuthenticatedUser principal) {
        // A donation adds stock, so only active staff of their own bank may record it, never ADMIN (Business Rule 8);
        // refused before anything is read, and the payload's bloodBankId is never consulted.
        BloodBank bank = actingBankResolver.resolveForStockChange(principal, "record a donation");
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType testedBloodType = BloodType.fromLabel(payload.bloodType().trim());

        LocalDate today = LocalDate.now();

        // The batch's expiry: as entered (validated here, before any write) or the default shelf life.
        LocalDate expiryDate = payload.expiryDate() != null
            ? payload.expiryDate()
            : StockPolicy.defaultDonationExpiry(today);
        inventoryService.requireAcceptableExpiry(expiryDate);

        Donor donor = donorRepository.findByIdForUpdate(payload.donorId())
            .orElseThrow(() -> new NotFoundException("No donor found for this id."));

        requireEligible(donor);

        // 1. What was drawn and tested: the group entered from today's test (Business Rule 4);
        // donor.getBloodType() is never read here.
        Donation donation = new Donation();
        donation.setDonor(donor);
        donation.setBloodBank(bank);
        donation.setDonationDate(today);
        donation.setUnitsDonated(payload.unitsDonated());
        donation.setBloodType(testedBloodType);
        Donation saved = donationRepository.save(donation);

        // 2. The clock Business Rule 2 measures from. Without this the donor stays
        //    "eligible" and could be appealed to — or recorded — again the same week.
        donor.setLastDonationDate(today);
        donorRepository.save(donor);

        // 3. The units, as a new batch linked to this donation (Business Rule 24); receiveDonation also runs
        // the low-stock check and cooldown recovery, as Add New Batch does.
        InventoryItemResponse stock = inventoryService.receiveDonation(saved, expiryDate);

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
            stock.status(),
            expiryDate
        );
    }

    /**
     * Refuses a donor inside their recovery window with a 409 (the donor's state clashes, not the request),
     * naming the date a donation becomes possible.
     */
    private void requireEligible(Donor donor) {
        if (donor.isEligibleToDonate()) {
            return;
        }
        throw new ConflictException(
            "%s cannot donate yet — they last donated on %s, and 90 days must pass between donations. "
                .formatted(donor.getUser().getFullName(), DATE.format(donor.getLastDonationDate()))
            + "A donation can be recorded from %s.".formatted(DATE.format(donor.eligibleFrom()))
        );
    }
}
