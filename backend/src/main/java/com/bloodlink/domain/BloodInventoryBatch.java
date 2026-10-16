package com.bloodlink.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.BatchSource;
import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One batch of blood units at one bank, with its own expiry date — the batch-stock
 * redesign (table V18). Replaces the one-row-per-(bank, group) {@link BloodInventory}
 * at cutover (V19); until then nothing in the application uses this class.
 *
 * <h2>What can change, and what cannot</h2>
 * Only {@link #unitsRemaining}, {@link #lastUpdated} and {@link #expiryNoticedAt}
 * ever change. Every other column is the batch's identity — which bank, which
 * group, how many arrived, when it expires, where it came from — and is fixed
 * once the batch exists, in three places: no setter here, {@code updatable = false}
 * on the mapping, and V18's trigger in the database. The expiry date in particular
 * must never move, because issuing earliest-expiry-first relies on the order being
 * stable. A wrongly entered batch is written off (DATA_ENTRY_ERROR) and re-added.
 *
 * Batches are never deleted: one that has been fully used stays as history, with
 * zero units remaining.
 */
@Entity
@Table(name = "blood_inventory_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class BloodInventoryBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_bank_id", nullable = false, updatable = false)
    private BloodBank bloodBank;

    @Column(name = "blood_type", nullable = false, length = 3, updatable = false)
    private BloodType bloodType;

    @Column(name = "units_received", nullable = false, updatable = false)
    private int unitsReceived;

    @Column(name = "units_remaining", nullable = false)
    private int unitsRemaining;

    /** The last day the batch may be used, inclusive (see StockPolicy). */
    @Column(name = "expiry_date", nullable = false, updatable = false)
    private LocalDate expiryDate;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    /** The donation these units came from; null unless {@link #source} is DONATION. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donation_id", unique = true, updatable = false)
    private Donation donation;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private BatchSource source;

    /** Reserved for the future expiry-rollover job; nothing sets it yet. */
    @Column(name = "expiry_noticed_at")
    private Instant expiryNoticedAt;

    /** A batch made by Record Donation: the donation's bank, tested group and units. */
    public static BloodInventoryBatch fromDonation(Donation donation, LocalDate expiryDate) {
        BloodInventoryBatch batch = create(
            donation.getBloodBank(), donation.getBloodType(), donation.getUnitsDonated(), expiryDate, BatchSource.DONATION);
        batch.donation = donation;
        return batch;
    }

    /** A batch entered with Add New Batch. */
    public static BloodInventoryBatch manual(BloodBank bank, BloodType bloodType, int units, LocalDate expiryDate) {
        return create(bank, bloodType, units, expiryDate, BatchSource.MANUAL);
    }

    private static BloodInventoryBatch create(
        BloodBank bank, BloodType bloodType, int units, LocalDate expiryDate, BatchSource source
    ) {
        if (units <= 0) {
            throw new IllegalArgumentException("A batch must contain at least one unit.");
        }
        BloodInventoryBatch batch = new BloodInventoryBatch();
        batch.bloodBank = bank;
        batch.bloodType = bloodType;
        batch.unitsReceived = units;
        batch.unitsRemaining = units;
        batch.expiryDate = expiryDate;
        batch.source = source;
        return batch;
    }

    /**
     * Takes units off the shelf: an issue to a hospital or a write-off. Refuses
     * anything that would take more than is left, the same rule V18's CHECK
     * enforces in the database, so the mistake fails here at the line that made it.
     */
    public void deductUnits(int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("Units to deduct must be positive.");
        }
        if (units > unitsRemaining) {
            throw new IllegalArgumentException(
                "Cannot deduct " + units + " units from a batch with " + unitsRemaining + " remaining.");
        }
        unitsRemaining -= units;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (receivedAt == null) {
            receivedAt = now;
        }
        lastUpdated = now;
    }

    @PreUpdate
    void onUpdate() {
        lastUpdated = Instant.now();
    }
}
