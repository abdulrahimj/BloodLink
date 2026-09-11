package com.bloodlink.inventory.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * "Add New Batch" — records a freshly tested/received batch against one bank's
 * (bank, blood group) inventory row, creating it if this is the first stock ever
 * held for that group.
 *
 * There is no per-batch table in the schema (BloodInventory is "one row per
 * (blood_bank_id, blood_type) — not one row per unit"), so a batch is an upsert:
 * it adds to the existing unit count rather than replacing it. See
 * InventoryService.addBatch for how the single expiry_date column is kept
 * meaningful across multiple batches.
 */
public record AddBatchRequest(

    /**
     * Bounded at 1000 as a typo guard, not a clinical rule — the same framing
     * RecordDonationPayload.unitsDonated already gives its own, much smaller cap.
     * A regional blood bank's single batch is realistically dozens to low
     * hundreds of units; 1000 leaves generous room for a genuine large shipment
     * while still catching a stray extra digit before it enters issuable stock.
     */
    @NotNull(message = "Units to add is required")
    @Min(value = 1, message = "Must add at least 1 unit")
    @Max(value = 1000, message = "That is more units than a single batch should carry — please check the figure")
    Integer unitsToAdd,

    @NotNull(message = "Expiry date is required")
    @FutureOrPresent(message = "Expiry date cannot be in the past")
    LocalDate expiryDate
) {}
