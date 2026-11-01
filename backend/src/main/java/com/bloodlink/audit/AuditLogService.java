package com.bloodlink.audit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.dto.AuditLogEntryResponse;
import com.bloodlink.audit.dto.AuditLogPageResponse;
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.UserRepository;

/**
 * Records and reads the audit log (Business Rule 17); nothing here or in AuditLogRepository changes an entry,
 * and V12's triggers would refuse it anyway.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    AuditLogService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * Records one administrator action. MANDATORY: it must run in the caller's transaction so the entry and the action
     * commit or roll back together. The actor is referenced by id without loading the row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, AuditAction action, User target, String details) {
        User actor = userRepository.getReferenceById(actorId);
        auditLogRepository.save(AuditLog.record(actor, action, target, details));
    }

    /**
     * Records a new blood bank as BLOOD_BANK_CREATED (Business Rule 19), in the caller's transaction like record.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBloodBankCreated(UUID actorId, BloodBank bank, String details) {
        User actor = userRepository.getReferenceById(actorId);
        auditLogRepository.save(AuditLog.recordBloodBankCreated(actor, bank, details));
    }

    /** Records a new hospital as HOSPITAL_CREATED (Business Rule 19). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordHospitalCreated(UUID actorId, Hospital hospital, String details) {
        User actor = userRepository.getReferenceById(actorId);
        auditLogRepository.save(AuditLog.recordHospitalCreated(actor, hospital, details));
    }

    /**
     * Records a write-off as STOCK_WRITTEN_OFF (Business Rule 24), committing with the write-off and its stock change.
     * The actor is the bank staff member who discarded the units, never an administrator.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStockWrittenOff(UUID actorId, BloodInventoryBatch batch, String details, Instant at) {
        User actor = userRepository.getReferenceById(actorId);
        auditLogRepository.save(AuditLog.recordStockWrittenOff(actor, batch, details, at));
    }

    /**
     * Records a locked phone number as SIGN_IN_LOCKED (Business Rule 34). Nobody signed in did this, so it has no actor;
     * account is null when the number is unregistered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSignInLocked(User account, String details, Instant at) {
        auditLogRepository.save(AuditLog.recordSignInLocked(account, details, at));
    }

    /**
     * One page of entries, newest first; page and size were validated by AuditLogController.
     * The order (with id tie-breaker) is fixed only in findAllNewestFirst's JPQL.
     */
    @Transactional(readOnly = true)
    public AuditLogPageResponse list(int page, int size) {
        Page<AuditLogEntryResponse> result = auditLogRepository
            .findAllNewestFirst(PageRequest.of(page, size))
            .map(AuditLogEntryResponse::from);

        return new AuditLogPageResponse(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages()
        );
    }
}
