package com.bloodlink.inventory.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * "Add New Batch" — records a freshly tested or received batch of one blood group
 * at one bank. Each call creates a NEW batch with its own expiry date (Business
 * Rule 24); nothing is merged into existing stock, and no earlier date is inherited.
 *
 * The expiry date's range (today, up to 365 days ahead) is checked by
 * InventoryService against StockPolicy rather than by an annotation here: the rule
 * is shared with Record Donation, and "today" must be the UTC date StockPolicy uses
 * everywhere else, not the server's local date that {@code @FutureOrPresent} would
 * read. A date outside the range is still a 400 on the {@code expiryDate} field.
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

    /** The last day the batch may be used, inclusive (StockPolicy). */
    @NotNull(message = "Expiry date is required")
    LocalDate expiryDate
) {}
