package com.bloodlink.audit.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;

/**
 * One row of the Audit Log page, with actor and subject ids and names (current names; details as written then).
 * targetType says if the subject is an account, a facility or a batch; a batch is described by group, expiry and bank.
 */
public record AuditLogEntryResponse(
    UUID id,
    AuditAction action,
    UUID actorId,
    String actorName,
    TargetType targetType,
    UUID targetId,
    String targetName,
    Role targetRole,
    String details,
    Instant createdAt
) {

    /** What kind of thing an entry's subject is. */
    public enum TargetType { USER, BLOOD_BANK, HOSPITAL, BATCH }

    /** Must run inside the transaction that loaded the entry, since the actor and subject are lazy
     * (AuditLogRepository fetch-joins them). */
    public static AuditLogEntryResponse from(AuditLog entry) {
        TargetType type;
        UUID targetId;
        String targetName;
        Role targetRole = null;
        if (entry.getTargetBloodBank() != null) {
            type = TargetType.BLOOD_BANK;
            targetId = entry.getTargetBloodBank().getId();
            targetName = entry.getTargetBloodBank().getName();
        } else if (entry.getTargetHospital() != null) {
            type = TargetType.HOSPITAL;
            targetId = entry.getTargetHospital().getId();
            targetName = entry.getTargetHospital().getName();
        } else if (entry.getTargetBatch() != null) {
            type = TargetType.BATCH;
            targetId = entry.getTargetBatch().getId();
            targetName = describeBatch(entry.getTargetBatch());
        } else {
            type = TargetType.USER;
            targetId = entry.getTarget().getId();
            targetName = entry.getTarget().getFullName();
            targetRole = entry.getTarget().getRole();
        }

        return new AuditLogEntryResponse(
            entry.getId(),
            entry.getAction(),
            entry.getActor().getId(),
            entry.getActor().getFullName(),
            type,
            targetId,
            targetName,
            targetRole,
            entry.getDetails(),
            entry.getCreatedAt()
        );
    }

    /** A batch's stand-in for a name: its fixed group and expiry date, plus its bank's current name. */
    private static String describeBatch(BloodInventoryBatch batch) {
        return "%s batch, expiry %s — %s".formatted(
            batch.getBloodType().getLabel(), batch.getExpiryDate(), batch.getBloodBank().getName());
    }
}
