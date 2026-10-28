package com.bloodlink.donor;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.donor.dto.CorrectBloodGroupPayload;
import com.bloodlink.donor.dto.DonorBloodGroupResponse;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * A donor's profile blood group follows the blood bank's test (Business Rule 28): set or changed from a recorded
 * donation, or corrected by hand after a test. Every change is audited as DONOR_BLOOD_GROUP_UPDATED.
 */
@Service
public class DonorBloodGroupService {

    /** ApiError.code on the 409 for a tested group that differs from the profile's, sent without confirmation. */
    public static final String CHANGE_UNCONFIRMED_CODE = "BLOOD_GROUP_CHANGE_UNCONFIRMED";

    private final DonorRepository donorRepository;
    private final ActingBankResolver actingBankResolver;
    private final AuditLogService auditLogService;

    public DonorBloodGroupService(
        DonorRepository donorRepository,
        ActingBankResolver actingBankResolver,
        AuditLogService auditLogService
    ) {
        this.donorRepository = donorRepository;
        this.actingBankResolver = actingBankResolver;
        this.auditLogService = auditLogService;
    }

    /**
     * Refuses (409, BLOOD_GROUP_CHANGE_UNCONFIRMED) a tested group that would change a recorded profile group
     * without the staff member's confirmation. Reads and writes nothing; called before a donation's first write.
     */
    public void requireConfirmedIfChanging(Donor donor, BloodType tested, boolean confirmed) {
        BloodType profile = donor.getBloodType();
        if (profile == null || profile == tested || confirmed) {
            return;
        }
        throw new ConflictException(CHANGE_UNCONFIRMED_CODE,
            "This donor's profile says %s, but the tested group is %s. Confirm the change to update their profile."
                .formatted(profile.getLabel(), tested.getLabel()));
    }

    /**
     * After a donation: sets the locked donor's profile group to the tested one if it differs, and audits it,
     * in the donation's transaction. True when the profile changed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean updateFromDonation(Donor donor, BloodType tested, BloodBank bank, UUID actorId) {
        return apply(donor, tested, actorId, "donation at " + bank.getName(), null);
    }

    /**
     * Manual correction after a test: active BLOOD_BANK_STAFF of any bank (checked before any read), any donor,
     * since donors are city-wide. A note is required; the same group as now is a 400.
     */
    @Transactional
    public DonorBloodGroupResponse correct(UUID donorId, CorrectBloodGroupPayload payload, AuthenticatedUser principal) {
        BloodBank bank = actingBankResolver.resolveForStockChange(principal, "correct a donor's blood group");
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType newGroup = BloodType.fromLabel(payload.bloodType().trim());
        String note = payload.note().trim();

        // Locked like Record Donation, so a correction and a donation for one donor cannot interleave.
        Donor donor = donorRepository.findByIdForUpdate(donorId)
            .orElseThrow(() -> new NotFoundException("No donor found for this id."));
        BloodType previous = donor.getBloodType();
        if (previous == newGroup) {
            throw new InvalidFieldException("bloodType",
                "%s's blood group is already %s.".formatted(donor.getUser().getFullName(), newGroup.getLabel()));
        }

        apply(donor, newGroup, principal.getId(), "manual correction by staff of " + bank.getName(), note);
        return new DonorBloodGroupResponse(
            donor.getId(),
            donor.getUser().getFullName(),
            previous == null ? null : previous.getLabel(),
            newGroup.getLabel()
        );
    }

    /** Writes the new group and its audit entry together; does nothing when the group is unchanged. */
    private boolean apply(Donor donor, BloodType newGroup, UUID actorId, String source, String note) {
        BloodType previous = donor.getBloodType();
        if (previous == newGroup) {
            return false;
        }
        donor.setBloodType(newGroup);
        donorRepository.save(donor);
        auditLogService.record(actorId, AuditAction.DONOR_BLOOD_GROUP_UPDATED, donor.getUser(),
            describe(previous, newGroup, source, note));
        return true;
    }

    /** The audit entry's details, e.g. "Blood group changed from A+ to B+. Source: donation at X." */
    static String describe(BloodType previous, BloodType newGroup, String source, String note) {
        String change = previous == null
            ? "Blood group set to %s (none was recorded before).".formatted(newGroup.getLabel())
            : "Blood group changed from %s to %s.".formatted(previous.getLabel(), newGroup.getLabel());
        String details = change + " Source: " + source + ".";
        return note == null ? details : details + " Note: " + note;
    }
}
