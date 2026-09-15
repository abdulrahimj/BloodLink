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
 * When one blood bank last appealed for one blood group, and how short it was at
 * the time — the state behind the Send Appeal button's cooldown (Business Rule 12).
 *
 * One row per (blood_bank, blood_type), overwritten on each send rather than
 * appended to: this is a *current state* row, not a history. Nothing in this
 * project reads past appeals — an appeal lives on as Notification rows in each
 * recipient's bell, as AppealController's own comment explains — and the only
 * question asked of this table is "may this bank appeal for this group right
 * now?", which only the most recent send can answer.
 *
 * {@code triggeredTier} is stored rather than re-derived from current stock,
 * because it records how bad things were *when the appeal went out*. Deriving it
 * later from current units would change a cooldown's length and recovery
 * threshold retroactively every time stock moved, which is the opposite of what
 * a cooldown is for.
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
