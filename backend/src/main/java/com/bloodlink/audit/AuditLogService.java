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
 * Writes and reads the administrators' audit log — Business Rule 17.
 *
 * Two operations and no third: an entry can be recorded, and the log can be read.
 * There is no method here — and none on AuditLogRepository — that changes or
 * removes an entry, and V12's triggers refuse either at the database even if
 * one were added.
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
     * Records one administrator action.
     *
     * {@code MANDATORY}, not the usual REQUIRED: this refuses to run unless the
     * caller already has a transaction open. The entry has to commit or roll back
     * together with the action it describes — an account suspended with no entry,
     * or an entry for a suspension that then failed, would both make the log say
     * something untrue. Requiring the caller's transaction, rather than quietly
     * starting a separate one, makes that impossible to get wrong later.
     *
     * The actor is referenced by id without loading their row (getReferenceById):
     * the log needs the foreign key, not the administrator's details.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, AuditAction action, User target, String details) {
        User actor = userRepository.getReferenceById(actorId);
        auditLogRepository.save(AuditLog.record(actor, action, target, details));
    }

    /**
     * Records a new blood bank as BLOOD_BANK_CREATED (Business Rule 19). MANDATORY
     * for the same reason as {@link #record}: the bank and its entry commit or roll
     * back together.
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
     * Records units written off a batch as STOCK_WRITTEN_OFF (Business Rule 24).
     * MANDATORY for the same reason as {@link #record}: the write-off, the stock it
     * took off the shelf and this entry commit or roll back together. The actor is
     * the blood bank staff member or administrator who discarded the units.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStockWrittenOff(UUID actorId, BloodInventoryBatch batch, String details, Instant at) {
        User actor = userRepository.getReferenceById(actorId);
        auditLogRepository.save(AuditLog.recordStockWrittenOff(actor, batch, details, at));
    }

    /**
     * One page of entries, newest first, for the Audit Log page.
     *
     * {@code page} and {@code size} have already been validated by
     * {@link AuditLogController} (page &gt;= 0, 1 &lt;= size &lt;= 100) by the time
     * this runs. The {@link PageRequest} is built with no {@link
     * org.springframework.data.domain.Sort} of its own — the newest-first order,
     * including the id tie-breaker, is fixed in {@code findAllNewestFirst}'s own
     * JPQL, so there is exactly one place that decides it, not two that could
     * disagree.
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
