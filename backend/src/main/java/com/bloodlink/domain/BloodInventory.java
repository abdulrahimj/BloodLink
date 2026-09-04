package com.bloodlink.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per (blood_bank_id, blood_type) — an aggregate count, not one row per unit.
 */
@Entity
@Table(
    name = "blood_inventory",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_blood_inventory_bank_type",
        columnNames = {"blood_bank_id", "blood_type"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class BloodInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_bank_id", nullable = false)
    private BloodBank bloodBank;

    @Column(name = "blood_type", nullable = false, length = 3)
    private BloodType bloodType;

    @Column(name = "units_available", nullable = false)
    private int unitsAvailable;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @PrePersist
    @PreUpdate
    void touch() {
        lastUpdated = Instant.now();
    }
}
