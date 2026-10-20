package com.bloodlink.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One bank's issue of units towards one request (Business Rule 20, V15): the source of truth for coverage
 * and contributors. No unique (request, bank): a bank may give more than once. Written with its stock deduction.
 */
@Entity
@Table(name = "blood_request_contributions")
@Getter
@Setter
@NoArgsConstructor
public class BloodRequestContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_request_id", nullable = false, updatable = false)
    private BloodRequest bloodRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_bank_id", nullable = false, updatable = false)
    private BloodBank bloodBank;

    /** Units issued in this one act — always positive (V15 CHECK). */
    @Column(name = "units_contributed", nullable = false, updatable = false)
    private int unitsContributed;

    @Column(name = "contributed_at", nullable = false, updatable = false)
    private Instant contributedAt;

    @PrePersist
    void onCreate() {
        if (contributedAt == null) {
            contributedAt = Instant.now();
        }
    }
}
