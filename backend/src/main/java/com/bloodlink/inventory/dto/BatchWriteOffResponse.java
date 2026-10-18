package com.bloodlink.inventory.dto;

/**
 * What a write-off recorded, and the batch as it now stands — the 201 response of
 * POST /api/inventory/{bloodBankId}/batches/{batchId}/write-offs.
 *
 * {@code writeOff} has the same shape as an entry in the batch history's
 * {@code writeOffs} list, so a screen can append it to that list as it is.
 */
public record BatchWriteOffResponse(
    BatchHistoryResponse.WriteOffEntry writeOff,
    BatchResponse batch
) {}
