package com.bloodlink.audit.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;

/**
 * One row of the Admin Console's Audit Log page.
 *
 * Carries both people's ids and names, so the page can show who did what to whom
 * without looking anyone up, plus the affected account's role for context. Names
 * are read as they are now; {@code details} is what was written at the time.
 */
public record AuditLogEntryResponse(
    UUID id,
    AuditAction action,
    UUID actorId,
    String actorName,
    UUID targetId,
    String targetName,
    Role targetRole,
    String details,
    Instant createdAt
) {

    /** Must run inside the transaction that loaded the entry — both people are
     *  lazy associations (AuditLogRepository fetch-joins them for this reason). */
    public static AuditLogEntryResponse from(AuditLog entry) {
        return new AuditLogEntryResponse(
            entry.getId(),
            entry.getAction(),
            entry.getActor().getId(),
            entry.getActor().getFullName(),
            entry.getTarget().getId(),
            entry.getTarget().getFullName(),
            entry.getTarget().getRole(),
            entry.getDetails(),
            entry.getCreatedAt()
        );
    }
}
