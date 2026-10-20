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
 * Create-only facilities from the Admin Console (Business Rule 19); existing rows are never touched.
 * A new bank needs no stock rows (missing groups read as 0), and each creation is audit-logged in the same transaction.
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
     * Creates a blood bank, refusing a name already used (409), compared case-insensitively like V14's index.
     * If two administrators race past the check, the index violation is translated into the same message.
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

    /** Creates a hospital with the same rules as createBloodBank; a hospital may share a name with a blood bank,
     * since uniqueness is within a type only. */
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
     * Trims and collapses runs of whitespace, so an extra space cannot slip a duplicate name past the checks.
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
