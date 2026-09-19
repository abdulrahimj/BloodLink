package com.bloodlink.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.dto.AuditLogEntryResponse;
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BloodBank;
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
     * Every entry, newest first, for the Audit Log page.
     *
     * The whole log in one response. Fine at this deployment's scale — a handful
     * of administrators creating, editing and suspending staff accounts produces
     * a few entries a week, not thousands — and it is what the page asks for. If
     * that ever changes, paging belongs here and in the query, as a decision for
     * the project owner.
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntryResponse> listAll() {
        return auditLogRepository.findAllNewestFirst().stream()
            .map(AuditLogEntryResponse::from)
            .toList();
    }
}
