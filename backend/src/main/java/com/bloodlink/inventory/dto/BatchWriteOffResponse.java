package com.bloodlink.inventory.dto;

/**
 * The 201 response of a write-off: the entry, shaped like a batch-history writeOffs item,
 * and the batch as it now stands.
 */
public record BatchWriteOffResponse(
    BatchHistoryResponse.WriteOffEntry writeOff,
    BatchResponse batch
) {}
