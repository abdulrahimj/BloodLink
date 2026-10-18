package com.bloodlink.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Write off units of one batch — POST
 * /api/inventory/{bloodBankId}/batches/{batchId}/write-offs (Business Rule 24).
 *
 * Only the lower bound of {@code units} is an annotation. The upper bound is the
 * batch's units remaining, which only the service can see once it holds the batch's
 * lock, so more than that is refused there (409). {@code reason} is a plain string
 * checked against WriteOffReason in the service, so an unknown one is a 400 naming
 * the "reason" field instead of the generic "could not be read" a failed enum
 * binding gives. The note's rules (required for OTHER, at most 500 characters) are
 * BatchWriteOff.problemWithNote's, applied in the service and keyed to "note".
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
