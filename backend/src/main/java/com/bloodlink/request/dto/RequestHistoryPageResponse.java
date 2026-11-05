package com.bloodlink.request.dto;

import java.util.List;

/**
 * One page of Request history (GET /api/requests/history), the same envelope as the Audit Log and the Donors roster.
 */
public record RequestHistoryPageResponse(
    List<RequestHistoryEntryResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
