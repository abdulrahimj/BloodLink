package com.bloodlink.donor.dto;

import java.util.List;

/**
 * One page of a blood bank's donor roster — the shape
 * {@code GET /api/donors/roster} returns (Business Rule 21).
 *
 * The same shape as the Audit Log's {@code AuditLogPageResponse} and for the
 * same reason: Spring Data's own {@code Page} carries internal fields
 * (pageable, sort, …) that are not part of this API's contract. Declared as
 * its own record rather than shared with the audit log's, since the two
 * features have no other reason to depend on one another.
 */
public record DonorRosterPageResponse(
    List<DonorRosterEntryResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
