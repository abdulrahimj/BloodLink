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
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One batch of units at one bank with its own expiry date (V18), the only record of stock since V19 (Business Rule 24).
 * Only units remaining, last updated and expiry noticed change; the identity is fixed, and batches are never deleted.
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
     * Takes units off the shelf (an issue or a write-off), refusing more than remain,
     * the same rule V18's CHECK enforces, so the mistake fails here.
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

    /**
     * The same deduction, stamping last_updated with the caller's clock instant instead of onUpdate's,
     * set now so a response built before the flush already shows it.
     */
    public void deductUnits(int units, Instant at) {
        deductUnits(units);
        lastUpdated = at;
        lastUpdatedGiven = true;
    }

    /**
     * Set by deductUnits(int, Instant) so the update hook keeps the given instant; not a column.
     * Never reset by the hook, since Hibernate may run it twice in one transaction.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    private boolean lastUpdatedGiven;

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
        if (!lastUpdatedGiven) {
            lastUpdated = Instant.now();
        }
    }
}
