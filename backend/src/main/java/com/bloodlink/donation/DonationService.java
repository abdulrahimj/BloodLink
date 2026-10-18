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
 * Step 3 makes the donation's units a batch of their own (Business Rule 24),
 * through {@link InventoryService#receiveDonation}, which owns what follows stock
 * rising for both ways units arrive. The batch is linked to the donation (one
 * donation, one batch) and carries the donation's units, so the shelf can always be
 * traced back to who gave the blood.
 *
 * The batch's expiry is the date the staff member enters, or, if they enter none,
 * donation date + StockPolicy.DONATION_SHELF_LIFE_DAYS (42, the usual shelf life of
 * red cells in additive solution; provisional until the project owner confirms the
 * figure Makeni's labs use). An entered date must be today or later and at most 365
 * days away, checked before anything is written.
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
     * Records one donation and everything that follows from it — for an eligible
     * donor only.
     *
     * A donor still inside Business Rule 2's 90-day recovery window is refused
     * outright with a 409, and nothing is written: no Donation row, no change to
     * their last-donation date, no units added. This is the enforcement point.
     * The Record Donation screen locks its form for the same donor, but that is a
     * courtesy that explains the refusal in advance; a request made directly
     * against this endpoint meets the same rule.
     *
     * The check asks {@link Donor#isEligibleToDonate()}, and the refusal quotes
     * {@link Donor#eligibleFrom()} — both on the entity, both built on the one
     * ELIGIBILITY_WINDOW_DAYS constant, and the same pair the donor's own
     * dashboard reads. The decision is taken from the status method rather than
     * by comparing today against eligibleFrom(), because for a donor who has
     * never given blood eligibleFrom() is their registration date in UTC, which
     * near midnight can fall on "tomorrow" in local time — comparing against it
     * would wrongly lock out a donor registered minutes ago. The status method
     * has no such edge; for everyone else the two are exactly equivalent.
     *
     * The donor row is read under a write lock before the check. The check
     * ("are they eligible?") and the write ("they donated today") are separate
     * statements, so without the lock two submissions for the same donor at the
     * same moment — a double-click, or two staff at two desks — would both read
     * an eligible donor, both pass, and record two donations. With it, the second
     * waits, re-reads a last-donation date of today, and is refused like any
     * other ineligible request.
     */
    @Transactional
    public DonationRecordedResponse recordDonation(RecordDonationPayload payload, AuthenticatedUser principal) {
        // Recording a donation puts units on a shelf (a new batch), so it is a stock
        // change: active staff of their own bank only, never an ADMIN on a named
        // bank's behalf (Business Rule 8, Stage 3b-2). Refused before anything is
        // read, so a refused caller causes no lookup, lock or write. The payload's
        // bloodBankId is therefore never consulted.
        BloodBank bank = actingBankResolver.resolveForStockChange(principal, "record a donation");
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType testedBloodType = BloodType.fromLabel(payload.bloodType().trim());

        LocalDate today = LocalDate.now();

        // The batch's expiry: as entered, or the default shelf life. An entered date
        // is checked here, before anything is written, so a typo in the date refuses
        // the whole action with a 400 on expiryDate rather than half of it.
        LocalDate expiryDate = payload.expiryDate() != null
            ? payload.expiryDate()
            : StockPolicy.defaultDonationExpiry(today);
        inventoryService.requireAcceptableExpiry(expiryDate);

        Donor donor = donorRepository.findByIdForUpdate(payload.donorId())
            .orElseThrow(() -> new NotFoundException("No donor found for this id."));

        requireEligible(donor);

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
        //    "eligible" and could be appealed to — or recorded — again the same week.
        donor.setLastDonationDate(today);
        donorRepository.save(donor);

        // 3. The units, as a new batch of the tested group linked to this donation
        //    (Business Rule 24: no merging into older stock, no inherited date).
        //    receiveDonation also runs the low-stock check and the appeal-cooldown
        //    recovery that follow stock rising, exactly as Add New Batch does.
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
     * Refuses a donor inside their recovery window, saying when and why.
     *
     * 409 rather than 400 or 403: the request is well-formed and the caller is
     * allowed to record donations — it is the donor's current state that clashes,
     * the same category as a request that can no longer be approved because
     * another bank already took it. The message names the date a donation
     * becomes possible, so the staff member has something to act on rather than
     * a bare "not allowed".
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
