package com.bloodlink.domain.enums;

/**
 * Where a batch of stock came from (V18, {@code blood_inventory_batches.source}).
 * Stored by name; V18's CHECK constraint lists the same three values.
 */
public enum BatchSource {

    /** Made by Record Donation; the batch names its donation (V18 enforces both ways). */
    DONATION,

    /** Entered with Add New Batch: received or tested stock not tied to one donation. */
    MANUAL,

    /**
     * Converted from the old blood_inventory table by V19; its expiry is the old merged date,
     * so staff should check it against the bag labels.
     */
    MIGRATED
}
