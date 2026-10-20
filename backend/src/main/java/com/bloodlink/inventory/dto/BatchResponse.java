package com.bloodlink.inventory.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.enums.BatchSource;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.stock.StockPolicy.ExpiryState;

/**
 * One batch of stock as the batch list shows it (bank staff and admins only);
 * state and daysUntilExpiry come from StockPolicy on the server.
 */
public record BatchResponse(
    UUID id,
    String bloodType,
    int unitsReceived,
    int unitsRemaining,
    LocalDate expiryDate,
    /** USABLE, EXPIRING_SOON (still usable) or EXPIRED, as of today in UTC. */
    ExpiryState state,
    /** 0 on the expiry day itself, negative once expired. */
    long daysUntilExpiry,
    Instant receivedAt,
    Instant lastUpdated,
    /** DONATION, MANUAL (Add New Batch) or MIGRATED (converted at the V19 cutover,
     *  whose date should be checked against the bag labels). */
    BatchSource source,
    /** The donation the units came from; null unless the source is DONATION. */
    UUID donationId
) {

    /** Must run inside the transaction that loaded the batch (the donation is lazy). */
    public static BatchResponse from(BloodInventoryBatch batch, LocalDate today) {
        return new BatchResponse(
            batch.getId(),
            batch.getBloodType().getLabel(),
            batch.getUnitsReceived(),
            batch.getUnitsRemaining(),
            batch.getExpiryDate(),
            StockPolicy.stateOf(batch.getExpiryDate(), today),
            StockPolicy.daysUntilExpiry(batch.getExpiryDate(), today),
            batch.getReceivedAt(),
            batch.getLastUpdated(),
            batch.getSource(),
            batch.getDonation() == null ? null : batch.getDonation().getId()
        );
    }
}
