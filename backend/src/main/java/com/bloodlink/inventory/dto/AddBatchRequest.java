package com.bloodlink.inventory.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * "Add New Batch": each call creates a NEW batch with its own expiry date (Business Rule 24).
 * The expiry range is checked by InventoryService against StockPolicy's UTC today, still a 400 on expiryDate.
 */
public record AddBatchRequest(

    /**
     * Capped at 1000 as a typo guard, not a clinical rule, catching a stray extra digit.
     */
    @NotNull(message = "Units to add is required")
    @Min(value = 1, message = "Must add at least 1 unit")
    @Max(value = 1000, message = "That is more units than a single batch should carry — please check the figure")
    Integer unitsToAdd,

    /** The last day the batch may be used, inclusive (StockPolicy). */
    @NotNull(message = "Expiry date is required")
    LocalDate expiryDate
) {}
