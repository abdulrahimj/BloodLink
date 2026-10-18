package com.bloodlink.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bloodlink.domain.enums.WriteOffReason;

/**
 * One batch and everything that has happened to its units — GET
 * /api/inventory/{bloodBankId}/batches/{batchId}. Bank staff of that bank and
 * administrators only.
 *
 * The figures always reconcile: unitsReceived = unitsRemaining + unitsIssued +
 * unitsWrittenOff (the stock identity, Business Rule 24). For a MIGRATED batch that
 * history starts at the V19 cutover; what happened to its units before then was never
 * recorded by the old one-row-per-group table.
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
