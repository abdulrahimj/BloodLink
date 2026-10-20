package com.bloodlink.donor.dto;

import java.util.List;

/**
 * One page of a bank's donor roster (GET /api/donors/roster), the same envelope as the Audit Log's,
 * declared separately since the two features are unrelated.
 */
public record DonorRosterPageResponse(
    List<DonorRosterEntryResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
