package com.bloodlink.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.DonationBooking;

/**
 * Donation bookings (Business Rule 29). Every query here reads open (BOOKED) bookings only,
 * except the test-facing history finder.
 */
public interface DonationBookingRepository extends JpaRepository<DonationBooking, UUID> {

    /** The donor's one open booking, if any, with its bank fetched (V27 allows at most one). */
    @Query("""
        select b from DonationBooking b join fetch b.bloodBank
         where b.donor.id = :donorId and b.status = com.bloodlink.domain.enums.BookingStatus.BOOKED
        """)
    Optional<DonationBooking> findOpenByDonorId(@Param("donorId") UUID donorId);

    /** A bank's open bookings from :today on, donor and account fetched; sorted by date then morning first. */
    @Query("""
        select b from DonationBooking b join fetch b.donor d join fetch d.user
         where b.bloodBank.id = :bloodBankId and b.status = com.bloodlink.domain.enums.BookingStatus.BOOKED
           and b.bookingDate >= :today
         order by b.bookingDate asc,
                  case when b.timeOfDay = com.bloodlink.domain.enums.BookingTimeOfDay.MORNING then 0 else 1 end asc,
                  b.createdAt asc, b.id asc
        """)
    List<DonationBooking> findUpcomingForBank(@Param("bloodBankId") UUID bloodBankId, @Param("today") LocalDate today);

    /** Open bookings on :date whose reminder has not gone out, with bank, donor and account fetched. */
    @Query("""
        select b from DonationBooking b join fetch b.bloodBank join fetch b.donor d join fetch d.user
         where b.status = com.bloodlink.domain.enums.BookingStatus.BOOKED
           and b.bookingDate = :date and b.reminderSentAt is null
        """)
    List<DonationBooking> findDueReminders(@Param("date") LocalDate date);

    /**
     * Stamps one booking's reminder only if still open and unstamped; 1 means this caller won the right to send it.
     * The row lock the update takes makes a second server's stamp return 0.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        update DonationBooking b set b.reminderSentAt = :at
         where b.id = :id and b.reminderSentAt is null
           and b.status = com.bloodlink.domain.enums.BookingStatus.BOOKED
        """)
    int stampReminderSent(@Param("id") UUID id, @Param("at") Instant at);

    /** Closes every open booking dated before :today as MISSED; returns how many. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DonationBooking b
           set b.status = com.bloodlink.domain.enums.BookingStatus.MISSED, b.closedAt = :at
         where b.status = com.bloodlink.domain.enums.BookingStatus.BOOKED and b.bookingDate < :today
        """)
    int markMissedBefore(@Param("today") LocalDate today, @Param("at") Instant at);

    /** Every booking of one donor, oldest first — used by tests. */
    List<DonationBooking> findByDonorIdOrderByCreatedAtAsc(UUID donorId);
}
