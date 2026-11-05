package com.bloodlink.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestCancellationReason;
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
import lombok.AccessLevel;
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
     * Optional ward/patient label ("Maternity Ward — Bed 12") for the hospital's own use;
     * this system holds no patient records.
     */
    @Column(name = "patient_reference")
    private String patientReference;

    /**
     * The bank that contributed most recently, null until one has (Business Rule 20; renamed in V15).
     * A display convenience only: BloodRequestContribution is the source of truth for who gave what.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_contributed_by_blood_bank_id")
    private BloodBank lastContributedByBloodBank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // The four cancellation columns (Business Rule 35, V32): set together by cancel(), so no setters.
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 32)
    @Setter(AccessLevel.NONE)
    private RequestCancellationReason cancellationReason;

    @Column(name = "cancellation_note", length = 200)
    @Setter(AccessLevel.NONE)
    private String cancellationNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    @Setter(AccessLevel.NONE)
    private User cancelledBy;

    @Column(name = "cancelled_at")
    @Setter(AccessLevel.NONE)
    private Instant cancelledAt;

    /** When it finished (FULFILLED, REJECTED or CANCELLED), null while open (Business Rule 36, V33). */
    @Column(name = "closed_at")
    @Setter(AccessLevel.NONE)
    private Instant closedAt;

    /** Finishes the request by contribution or decline, recording when; cancelling has its own method below. */
    public void close(RequestStatus finalStatus, Instant at) {
        if (finalStatus.isOpen() || finalStatus == RequestStatus.CANCELLED) {
            throw new IllegalArgumentException("close() takes FULFILLED or REJECTED, not " + finalStatus);
        }
        status = finalStatus;
        closedAt = at;
    }

    /** The short id people see and say: "#3F2A9C1B", the first eight characters of the UUID in capitals. */
    public String getReference() {
        return id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** Closes the request as the hospital's own decision (Business Rule 35); RequestService checks it may be cancelled. */
    public void cancel(RequestCancellationReason reason, String note, User by, Instant at) {
        status = RequestStatus.CANCELLED;
        cancellationReason = reason;
        cancellationNote = note;
        cancelledBy = by;
        cancelledAt = at;
        closedAt = at;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
