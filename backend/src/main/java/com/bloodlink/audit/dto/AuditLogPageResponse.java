package com.bloodlink.audit.dto;

import java.util.List;

/**
 * One page of the Audit Log — the shape {@code GET /api/admin/audit-log} returns.
 *
 * A deliberate wrapper rather than returning Spring Data's {@code Page} directly:
 * {@code Page}'s own JSON shape carries Spring Data internals (a nested
 * {@code pageable}, {@code sort}, etc.) that are not part of this API's contract
 * and would be free to change under an unrelated Spring Data upgrade. This record
 * is the one field set the frontend is promised: the entries themselves, which
 * page they are, how big it is, and the totals needed to draw "Page X of Y".
 */
public record AuditLogPageResponse(
    List<AuditLogEntryResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
