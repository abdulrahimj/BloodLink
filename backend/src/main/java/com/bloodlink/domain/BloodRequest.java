package com.bloodlink.domain;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.UrgencyLevel;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "blood_requests")
@Getter
@Setter
@NoArgsConstructor
public class BloodRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "blood_type", nullable = false, length = 3)
    private BloodType bloodType;

    @Column(name = "units_requested", nullable = false)
    private int unitsRequested;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency_level", nullable = false, length = 16)
    private UrgencyLevel urgencyLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RequestStatus status;

    /**
     * Optional free-text ward / patient reference from the request form
     * ("Maternity Ward — Bed 12"). Purely a label the requesting hospital uses to
     * match delivered units back to the patient who needs them — this system holds
     * no patient records, by design.
     */
    @Column(name = "patient_reference")
    private String patientReference;

    /** Null until the request is approved; any blood bank may fulfil (Business Rule 6). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfilled_by_blood_bank_id")
    private BloodBank fulfilledByBloodBank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
