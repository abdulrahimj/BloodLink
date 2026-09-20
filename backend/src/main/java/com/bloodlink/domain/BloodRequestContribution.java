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
 * One blood bank's issue of units towards one hospital request — Business Rule 20,
 * table V15.
 *
 * This table is the source of truth for how much of a request has been covered and
 * by whom. {@link BloodRequest#getStatus()} and
 * {@link BloodRequest#getLastContributedByBloodBank()} are both derived from these
 * rows; neither can replace them, because a request may now be covered by several
 * banks and a single column cannot name several.
 *
 * Unlike {@link BloodRequestDecline} there is no unique constraint on
 * (request, bank): a decline is one final answer, but a bank may contribute to the
 * same request more than once — two units today, three when the next donation
 * session comes in — and each of those is its own act with its own timestamp.
 *
 * The units recorded here have already left the contributing bank's inventory: the
 * deduction and this row are written in the same transaction (RequestService), so
 * there is no state in which a contribution exists that stock was not taken for.
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
