package com.bloodlink.audit.dto;

import java.util.List;

/**
 * One page of the Audit Log (GET /api/admin/audit-log), wrapped rather than returning Spring Data's Page,
 * whose JSON carries internals that are not part of this API.
 */
public record AuditLogPageResponse(
    List<AuditLogEntryResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
