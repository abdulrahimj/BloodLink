package com.bloodlink.domain;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "donations")
@Getter
@Setter
@NoArgsConstructor
public class Donation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "donor_id", nullable = false)
    private Donor donor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_bank_id", nullable = false)
    private BloodBank bloodBank;

    @Column(name = "donation_date", nullable = false)
    private LocalDate donationDate;

    @Column(name = "units_donated", nullable = false)
    private int unitsDonated;

    /**
     * Independently recorded — verified by testing at donation time, not copied
     * from Donor.bloodType (CLAUDE.md Business Rule 4).
     */
    @Column(name = "blood_type", nullable = false, length = 3)
    private BloodType bloodType;
}
