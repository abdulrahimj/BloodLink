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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One blood bank's decision to decline one hospital request.
 *
 * This is the per-bank response table Business Rule 11 anticipated. Before it,
 * rejection was recorded only in {@link BloodRequest#getStatus()}, a single
 * shared field — so one bank declining closed the request for every bank, even
 * one that had the stock to fulfil it. A decline is now recorded here instead,
 * per (request, bank) pair, and the request's own status is left alone until
 * every blood bank has declined it.
 *
 * There is deliberately no reason field. Nothing in the agreed schema or the
 * existing screens captures or displays one, and inventing a column the Reject
 * dialog never fills in would leave a permanently-empty value that looks like
 * missing data rather than an absent feature.
 */
@Entity
@Table(
    name = "blood_request_declines",
    // Mirrors the database constraint from V9 rather than replacing it — the
    // database is the real guard (two operators clicking Reject at the same
    // instant are two transactions, which no in-JVM check can see across).
    // Declaring it here keeps Hibernate's schema validation aware of it.
    uniqueConstraints = @UniqueConstraint(
        name = "uq_decline_request_bank",
        columnNames = {"blood_request_id", "blood_bank_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class BloodRequestDecline {

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

    @Column(name = "declined_at", nullable = false, updatable = false)
    private Instant declinedAt;

    @PrePersist
    void onCreate() {
        if (declinedAt == null) {
            declinedAt = Instant.now();
        }
    }
}
