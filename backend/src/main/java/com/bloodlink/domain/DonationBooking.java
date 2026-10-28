package com.bloodlink.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.BookingStatus;
import com.bloodlink.domain.enums.BookingTimeOfDay;

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

/**
 * A donor's booked donation visit (Business Rule 29, V27): one bank, one date, morning or afternoon.
 * It never touches stock; the donation recorded at the visit does that.
 */
@Entity
@Table(name = "donation_bookings")
@Getter
@Setter
@NoArgsConstructor
public class DonationBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "donor_id", nullable = false, updatable = false)
    private Donor donor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_bank_id", nullable = false, updatable = false)
    private BloodBank bloodBank;

    @Column(name = "booking_date", nullable = false, updatable = false)
    private LocalDate bookingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_of_day", nullable = false, length = 16, updatable = false)
    private BookingTimeOfDay timeOfDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BookingStatus status = BookingStatus.BOOKED;

    /** The donation that attended this visit; set exactly when ATTENDED (V27 CHECK). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donation_id", unique = true)
    private Donation donation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When it left BOOKED; null exactly while BOOKED (V27 CHECK). */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** When the day-before reminder was sent; written only by a conditional bulk update, so it is sent once. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    /** Closes an open booking as CANCELLED or MISSED at the given instant. */
    public void close(BookingStatus finalStatus, Instant at) {
        status = finalStatus;
        closedAt = at;
    }

    /** Closes an open booking as ATTENDED by this donation. */
    public void attend(Donation attendedBy, Instant at) {
        donation = attendedBy;
        close(BookingStatus.ATTENDED, at);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
