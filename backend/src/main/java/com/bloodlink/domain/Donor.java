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
     * Nullable: a donor may register before knowing their group. Never authoritative (Business Rule 4):
     * each Donation's tested value is.
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
     * The recovery period between donations (Business Rule 2), kept on the entity so the donor's status,
     * their eligible-again date and the appeal safety filter all share one value.
     */
    public static final int ELIGIBILITY_WINDOW_DAYS = 90;

    /**
     * Derived, never stored (Business Rule 2): eligible when the last donation was more than 90 days ago, or never.
     * "More than" is strict, so eligibility begins on day 91.
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
     * The date a last donation must be strictly before for eligibility today (today - 90 days), computed only here.
     * Used by getEligibilityStatus and by the Donors roster and Admin Reports queries (ReportsIT checks they agree).
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
     * The date eligibility began or begins, as the donor dashboard and Record Donation lookup show it:
     * registration date if never donated, otherwise day 91 after the last donation.
     */
    @Transient
    public LocalDate eligibleFrom() {
        if (lastDonationDate == null) {
            return user.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return lastDonationDate.plusDays(ELIGIBILITY_WINDOW_DAYS + 1);
    }
}
