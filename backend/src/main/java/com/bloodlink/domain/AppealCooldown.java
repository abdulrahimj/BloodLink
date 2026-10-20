package com.bloodlink.domain;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.enums.AppealTier;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * When a bank last appealed for a blood group and how short it was then: the cooldown state (Business Rule 12).
 * One current-state row per (bank, group), overwritten on each send; triggeredTier is stored so its terms never shift.
 */
@Entity
@Table(
    name = "appeal_cooldowns",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_appeal_cooldown_bank_type",
        columnNames = {"blood_bank_id", "blood_type"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class AppealCooldown {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_bank_id", nullable = false, updatable = false)
    private BloodBank bloodBank;

    // Stored as its clinical label ('O-') by BloodTypeConverter, matching every
    // other blood_type column in this schema.
    @Column(name = "blood_type", nullable = false, length = 3, updatable = false)
    private BloodType bloodType;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_tier", nullable = false, length = 16)
    private AppealTier triggeredTier;
}
