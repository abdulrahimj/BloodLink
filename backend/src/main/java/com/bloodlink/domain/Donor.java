package com.bloodlink.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.EligibilityStatus;
import com.bloodlink.domain.enums.Gender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "donors")
@Getter
@Setter
@NoArgsConstructor
public class Donor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * Nullable: a donor may register before knowing their group ("I don't know yet").
     * Business Rule 4 means this field is never authoritative anyway — the tested
     * value on each Donation record is. See V2__auth_constraints.sql.
     */
    @Column(name = "blood_type", length = 3)
    private BloodType bloodType;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    // length 32 (widened in V3): PREFER_NOT_TO_SAY does not fit the original 16.
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 32)
    private Gender gender;

    @Column(name = "last_donation_date")
    private LocalDate lastDonationDate;

    @Column(name = "address", nullable = false)
    private String address;

    /**
     * The recovery period between donations (CLAUDE.md Business Rule 2).
     *
     * Lives here, on the entity that owns the rule, because three things now depend
     * on it agreeing exactly: the status a donor is shown, the date their portal
     * says they become eligible again, and — since it became a safety filter —
     * which donors an appeal is allowed to reach. It was previously written out in
     * two places; a change to one and not the other would have quietly let an
     * appeal reach someone the same system was telling "you cannot donate yet".
     */
    public static final int ELIGIBILITY_WINDOW_DAYS = 90;

    /**
     * Derived, never stored (CLAUDE.md Business Rule 2): eligible when the last
     * donation was more than 90 days ago, or there has never been one.
     *
     * "More than" is strict, so the boundary day is still ineligible and eligibility
     * begins on day 91 — which is what DonorService.eligibleSince reports back to
     * the donor, and why it adds one to the window.
     */
    @Transient
    public EligibilityStatus getEligibilityStatus() {
        if (lastDonationDate == null) {
            return EligibilityStatus.ELIGIBLE;
        }
        return lastDonationDate.isBefore(LocalDate.now().minusDays(ELIGIBILITY_WINDOW_DAYS))
            ? EligibilityStatus.ELIGIBLE
            : EligibilityStatus.INELIGIBLE;
    }

    /** Reads as a sentence at the call sites that gate on this, and keeps the
     *  comparison against the enum in one place rather than at each of them. */
    @Transient
    public boolean isEligibleToDonate() {
        return getEligibilityStatus() == EligibilityStatus.ELIGIBLE;
    }
}
