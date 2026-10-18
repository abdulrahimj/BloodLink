package com.bloodlink.audit.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;

/**
 * One row of the Admin Console's Audit Log page.
 *
 * Carries the actor's and the subject's ids and names, so the page can show who
 * did what to whom without looking anything up. Names are read as they are now;
 * {@code details} is what was written at the time.
 *
 * The subject is an account for the four ACCOUNT_ actions, a facility for the
 * two creation actions (Business Rule 19), and a stock batch for a write-off
 * (Business Rule 24); {@code targetType} says which, so the page can label it.
 * {@code targetRole} is the account's role, and null for anything else — a blood
 * bank or a batch has no role. A batch has no name of its own, so its
 * {@code targetName} describes it: blood group, expiry date and bank, e.g.
 * "O+ batch, expiry 2026-10-01 — Makeni Regional Blood Bank".
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

    /** Must run inside the transaction that loaded the entry — the actor and the
     *  subject are lazy associations (AuditLogRepository fetch-joins them for
     *  this reason). */
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

    /** A batch's stand-in for a name: its group and expiry date, which can never
     *  change (V18 trigger), and its bank's name as it is now — like every other
     *  name on the page. */
    private static String describeBatch(BloodInventoryBatch batch) {
        return "%s batch, expiry %s — %s".formatted(
            batch.getBloodType().getLabel(), batch.getExpiryDate(), batch.getBloodBank().getName());
    }
}
