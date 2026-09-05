package com.bloodlink.inventory.dto;

import com.bloodlink.domain.enums.StockStatus;

/** One slice of the Stock Composition donut — total units currently at this status. */
public record CompositionSlice(
    StockStatus status,
    int units
) {}
