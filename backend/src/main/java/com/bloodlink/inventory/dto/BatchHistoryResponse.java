package com.bloodlink.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bloodlink.domain.enums.WriteOffReason;

/**
 * One batch and everything that happened to its units; bank staff and admins only.
 * unitsReceived = remaining + issued + written off; a MIGRATED batch's history starts at V19.
 */
public record BatchHistoryResponse(
    BatchResponse batch,
    /** Units of this batch issued to hospital requests. */
    int unitsIssued,
    /** Units of this batch written off (POST .../batches/{batchId}/write-offs). */
    int unitsWrittenOff,
    List<IssueEntry> issues,
    List<WriteOffEntry> writeOffs
) {

    /** Units of this batch that went to one contribution towards a hospital request. */
    public record IssueEntry(
        UUID contributionId,
        UUID requestId,
        String hospitalName,
        int units,
        Instant contributedAt
    ) {}

    /** Units of this batch discarded, why, and by whom. */
    public record WriteOffEntry(
        UUID id,
        int units,
        WriteOffReason reason,
        String note,
        String writtenOffByName,
        Instant writtenOffAt
    ) {}
}
