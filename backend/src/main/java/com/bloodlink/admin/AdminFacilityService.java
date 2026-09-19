package com.bloodlink.admin;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.CreateFacilityPayload;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.bloodbank.dto.BloodBankResponse;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.hospital.dto.HospitalResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;

/**
 * Adding blood banks and hospitals from the Admin Console — Business Rule 19.
 *
 * Create-only, deliberately. Editing or removing an existing facility would mean
 * deciding what happens to the staff, stock, requests and donations tied to it,
 * which is a separate decision for later; nothing here touches an existing row.
 *
 * <h2>What a new blood bank does not need</h2>
 * No BloodInventory rows are written. InventoryService.listInventory already
 * reports all eight blood groups for every bank, filling any group with no row as
 * zero units (and so Critical, Business Rule 3); the first "Add New Batch" or
 * recorded donation creates the real row. Seeding eight zero rows here would be a
 * second way of saying the same thing, and would need an expiry date that does not
 * exist for stock that does not exist.
 *
 * Every creation is recorded in the audit log in the same transaction
 * (Business Rule 17's pattern), naming the administrator who did it.
 */
@Service
public class AdminFacilityService {

    private final BloodBankRepository bloodBankRepository;
    private final HospitalRepository hospitalRepository;
    private final AuditLogService auditLogService;

    AdminFacilityService(
        BloodBankRepository bloodBankRepository,
        HospitalRepository hospitalRepository,
        AuditLogService auditLogService
    ) {
        this.bloodBankRepository = bloodBankRepository;
        this.hospitalRepository = hospitalRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Creates a blood bank, refusing a name another blood bank already has (409).
     *
     * The name check is case-insensitive, matching V14's unique index on
     * lower(name). The pre-flight check gives the clear message; if two
     * administrators race past it, the index refuses the second insert and that is
     * translated into the same message rather than the generic conflict text.
     */
    @Transactional
    public BloodBankResponse createBloodBank(CreateFacilityPayload payload, AuthenticatedUser principal) {
        String name = normaliseName(payload.name());
        if (bloodBankRepository.existsByNameIgnoreCase(name)) {
            throw duplicate("blood bank", name);
        }

        BloodBank bank = new BloodBank();
        bank.setName(name);
        bank.setLocation(payload.location().trim());
        bank.setContactPhone(payload.contactPhone().trim());

        BloodBank saved;
        try {
            // Flushed now, not at commit, so a lost race surfaces here as the
            // unique-index violation and can be reported as a duplicate name.
            saved = bloodBankRepository.saveAndFlush(bank);
        } catch (DataIntegrityViolationException raced) {
            throw duplicate("blood bank", name);
        }

        auditLogService.recordBloodBankCreated(principal.getId(), saved, describe("blood bank", saved.getName(),
            saved.getLocation(), saved.getContactPhone()));

        return new BloodBankResponse(saved.getId(), saved.getName(), saved.getLocation(), saved.getContactPhone());
    }

    /** Creates a hospital — the same rules as {@link #createBloodBank}, against the
     *  hospitals table. A hospital and a blood bank may share a name (Holy Spirit
     *  has both); uniqueness is within a type, not across them. */
    @Transactional
    public HospitalResponse createHospital(CreateFacilityPayload payload, AuthenticatedUser principal) {
        String name = normaliseName(payload.name());
        if (hospitalRepository.existsByNameIgnoreCase(name)) {
            throw duplicate("hospital", name);
        }

        Hospital hospital = new Hospital();
        hospital.setName(name);
        hospital.setLocation(payload.location().trim());
        hospital.setContactPhone(payload.contactPhone().trim());

        Hospital saved;
        try {
            saved = hospitalRepository.saveAndFlush(hospital);
        } catch (DataIntegrityViolationException raced) {
            throw duplicate("hospital", name);
        }

        auditLogService.recordHospitalCreated(principal.getId(), saved, describe("hospital", saved.getName(),
            saved.getLocation(), saved.getContactPhone()));

        return new HospitalResponse(saved.getId(), saved.getName(), saved.getLocation(), saved.getContactPhone());
    }

    /**
     * Trims the name and collapses runs of whitespace to one space, so
     * "Holy  Spirit Blood Bank " is stored — and compared — as the name it
     * obviously is. Without this, an extra space would slip a duplicate past the
     * case-insensitive check and the index alike.
     */
    static String normaliseName(String raw) {
        return raw.trim().replaceAll("\\s+", " ");
    }

    private static ConflictException duplicate(String kind, String name) {
        return new ConflictException("A " + kind + " named \"" + name + "\" already exists.");
    }

    /** The audit entry's details: what was created, as it was at that moment. */
    private static String describe(String kind, String name, String location, String contactPhone) {
        return "Created " + kind + " " + name + ", location " + location + ", contact phone " + contactPhone + ".";
    }
}
