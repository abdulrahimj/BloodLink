package com.bloodlink.domain;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
        return lastDonationDate.isBefore(eligibilityCutoff(LocalDate.now()))
            ? EligibilityStatus.ELIGIBLE
            : EligibilityStatus.INELIGIBLE;
    }

    /**
     * The date a last donation must be strictly before for the donor to be eligible
     * today: {@code today - 90 days}. The one place that date is worked out.
     *
     * {@link #getEligibilityStatus()} uses it, and so do the two database queries that
     * have to count or filter by eligibility rather than load every donor (the Donors
     * roster, Business Rule 21, and the Admin Reports page, Business Rule 23). Those
     * queries compare {@code last_donation_date is null or last_donation_date < cutoff},
     * which is this method's caller's own test written in SQL — and ReportsIT proves the
     * database count equals counting {@link #isEligibleToDonate()} donor by donor.
     */
    public static LocalDate eligibilityCutoff(LocalDate today) {
        return today.minusDays(ELIGIBILITY_WINDOW_DAYS);
    }

    /** Reads as a sentence at the call sites that gate on this, and keeps the
     *  comparison against the enum in one place rather than at each of them. */
    @Transient
    public boolean isEligibleToDonate() {
        return getEligibilityStatus() == EligibilityStatus.ELIGIBLE;
    }

    /**
     * The date eligibility began (already past, if they are eligible now) or
     * begins — the "eligible again from …" date both the donor's own dashboard
     * and the blood bank's Record Donation lookup show.
     *
     * A donor who has never given blood is eligible from the day they registered;
     * User.createdAt is the closest thing to that date the schema records.
     * Otherwise it is exactly one day past the window, because Business Rule 2's
     * "more than 90 days" is strict — the window's last day is still ineligible,
     * so eligibility starts on day 91.
     *
     * Lives on the entity rather than in a service so that this arithmetic and
     * {@link #getEligibilityStatus()} can never disagree about the same donor:
     * one of them saying "eligible" while the other names a future date would be
     * exactly the kind of silent contradiction a second copy of the rule invites.
     */
    @Transient
    public LocalDate eligibleFrom() {
        if (lastDonationDate == null) {
            return user.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return lastDonationDate.plusDays(ELIGIBILITY_WINDOW_DAYS + 1);
    }
}
