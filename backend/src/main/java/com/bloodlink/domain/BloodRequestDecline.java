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
 * One bank's decline of one request (Business Rule 11, V9); the request's status changes only once every bank declines.
 * Deliberately no reason field, since nothing captures or shows one.
 */
@Entity
@Table(
    name = "blood_request_declines",
    // Mirrors V9's database constraint, the real guard against two simultaneous declines;
    // declared so Hibernate's schema validation knows it.
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
