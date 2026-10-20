package com.bloodlink.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST .../batches/{batchId}/write-offs (Business Rule 24): only units' lower bound is an annotation;
 * the upper bound (409), reason (400 on reason) and note rules (400 on note) are checked in the service.
 */
public record WriteOffBatchRequest(

    @NotNull(message = "Units to write off is required")
    @Min(value = 1, message = "Must write off at least 1 unit")
    Integer units,

    /** EXPIRED, DAMAGED, FAILED_TESTING, DATA_ENTRY_ERROR or OTHER. */
    @NotBlank(message = "A reason is required")
    String reason,

    /** Optional, except for OTHER. Blank is stored as no note. */
    String note
) {}
