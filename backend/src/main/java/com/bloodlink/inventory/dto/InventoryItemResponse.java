package com.bloodlink.inventory.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.bloodlink.domain.enums.StockStatus;

/**
 * One blood group's stock at one bank — a row of GET /api/inventory/{bloodBankId}.
 *
 * A bank always reports all eight groups, even ones it has never stocked: those
 * come back with unitsAvailable 0, null expiryDate/lastUpdated, and status
 * CRITICAL (0 is below the 15-unit floor), matching what both mockups assume —
 * a fixed eight-row table, never a partial one.
 */
public record InventoryItemResponse(
    String bloodType,
    int unitsAvailable,
    LocalDate expiryDate,
    Instant lastUpdated,
    StockStatus status
) {}
