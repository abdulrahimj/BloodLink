package com.bloodlink.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.booking.dto.BookingResponse;
import com.bloodlink.booking.dto.CreateBookingPayload;
import com.bloodlink.booking.dto.UpcomingBookingResponse;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.DonationBooking;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BookingCancellation;
import com.bloodlink.domain.enums.BookingStatus;
import com.bloodlink.domain.enums.BookingTimeOfDay;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.DonationBookingRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Donation visits (Business Rule 29): an active, eligible donor books one bank, date and half-day; staff see their
 * bank's upcoming visits; a donation, a suspension or the daily job closes one. Never touches stock. "Today" is the UTC Clock.
 */
@Service
public class DonationBookingService {

    /** The furthest ahead a visit may be booked, in days from today. */
    public static final int MAX_DAYS_AHEAD = 30;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final DonationBookingRepository bookingRepository;
    private final DonorRepository donorRepository;
    private final BloodBankRepository bloodBankRepository;
    private final ActingBankResolver actingBankResolver;
    private final NotificationService notificationService;
    private final Clock clock;

    public DonationBookingService(
        DonationBookingRepository bookingRepository,
        DonorRepository donorRepository,
        BloodBankRepository bloodBankRepository,
        ActingBankResolver actingBankResolver,
        NotificationService notificationService,
        Clock clock
    ) {
        this.bookingRepository = bookingRepository;
        this.donorRepository = donorRepository;
        this.bloodBankRepository = bloodBankRepository;
        this.actingBankResolver = actingBankResolver;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /**
     * Books a visit for the caller: an ACTIVE donor eligible today (Business Rule 2, 409 otherwise) with no other open
     * booking (409). The date must be today to today + 30 (400 on bookingDate); the bank's staff are notified.
     */
    @Transactional
    public BookingResponse book(CreateBookingPayload payload, AuthenticatedUser principal) {
        Donor donor = lockActiveDonor(principal, "book a donation visit");
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();

        if (!donor.isEligibleToDonate()) {
            throw new ConflictException("You can book a donation from %s: 90 days must pass after your last donation."
                .formatted(DATE.format(donor.eligibleFrom())));
        }
        Optional<DonationBooking> open = bookingRepository.findOpenByDonorId(donor.getId());
        if (open.isPresent() && open.get().getBookingDate().isBefore(today)) {
            // Its date passed before the daily job got to it: it is missed, not in the way.
            open.get().close(BookingStatus.MISSED, now);
            bookingRepository.saveAndFlush(open.get());
        } else if (open.isPresent()) {
            throw new ConflictException(
                "You already have a donation booked for %s, %s at %s. Cancel it first to book a different visit."
                    .formatted(DATE.format(open.get().getBookingDate()), open.get().getTimeOfDay().inWords(),
                        open.get().getBloodBank().getName()));
        }
        requireBookableDate(payload.bookingDate(), today);
        BloodBank bank = bloodBankRepository.findById(payload.bloodBankId())
            .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));

        DonationBooking booking = new DonationBooking();
        booking.setDonor(donor);
        booking.setBloodBank(bank);
        booking.setBookingDate(payload.bookingDate());
        booking.setTimeOfDay(BookingTimeOfDay.valueOf(payload.timeOfDay()));
        booking.setCreatedAt(now);
        DonationBooking saved = bookingRepository.save(booking);

        notificationService.notifyBookingMade(saved);
        return BookingResponse.from(saved);
    }

    /**
     * Cancels the caller's open booking while its date is today or later (409 once it has passed; 404 if none),
     * and tells the bank's staff.
     */
    @Transactional
    public BookingResponse cancelMine(AuthenticatedUser principal) {
        Donor donor = lockActiveDonor(principal, "cancel a donation visit");
        DonationBooking booking = bookingRepository.findOpenByDonorId(donor.getId())
            .orElseThrow(() -> new NotFoundException("You have no booked donation visit to cancel."));
        if (booking.getBookingDate().isBefore(LocalDate.now(clock))) {
            throw new ConflictException("This visit's date has passed, so it can no longer be cancelled.");
        }

        booking.close(BookingStatus.CANCELLED, clock.instant());
        bookingRepository.save(booking);
        notificationService.notifyBookingCancelled(booking, BookingCancellation.BY_DONOR);
        return BookingResponse.from(booking);
    }

    /** The caller's open booking dated today or later, or empty; a past-dated one is about to be MISSED. */
    @Transactional(readOnly = true)
    public Optional<BookingResponse> currentForMe(AuthenticatedUser principal) {
        requireDonorRole(principal, "view a donation visit");
        LocalDate today = LocalDate.now(clock);
        return donorRepository.findByUserId(principal.getId())
            .flatMap(donor -> bookingRepository.findOpenByDonorId(donor.getId()))
            .filter(booking -> !booking.getBookingDate().isBefore(today))
            .map(BookingResponse::from);
    }

    /**
     * The caller's bank's upcoming visits (open, today or later), date then morning first. Active bank staff of
     * that bank only (ActingBankResolver, before any read): never ADMIN, hospital staff or donors.
     */
    @Transactional(readOnly = true)
    public List<UpcomingBookingResponse> upcomingForMyBank(AuthenticatedUser principal) {
        BloodBank bank = actingBankResolver.resolveForStockChange(principal, "view this bank's donation visits");
        return bookingRepository.findUpcomingForBank(bank.getId(), LocalDate.now(clock)).stream()
            .map(UpcomingBookingResponse::from)
            .toList();
    }

    /**
     * Record Donation closes the donor's open booking in its own transaction: ATTENDED and linked at the booked bank,
     * otherwise CANCELLED with the booked bank's staff told. The donor row is already locked by the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeOnDonation(Donor donor, Donation donation, BloodBank bank) {
        Optional<DonationBooking> open = bookingRepository.findOpenByDonorId(donor.getId());
        if (open.isEmpty()) {
            return;
        }
        DonationBooking booking = open.get();
        Instant now = clock.instant();
        if (booking.getBloodBank().getId().equals(bank.getId())) {
            booking.attend(donation, now);
            bookingRepository.save(booking);
        } else {
            booking.close(BookingStatus.CANCELLED, now);
            bookingRepository.save(booking);
            notificationService.notifyBookingCancelled(booking, BookingCancellation.DONATED_ELSEWHERE);
        }
    }

    /**
     * An administrator suspending a donor cancels their open booking in the same transaction and tells the bank's
     * staff; one whose date already passed is just MISSED. Reactivating restores nothing. No booking: nothing happens.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOnSuspension(UUID donorUserId) {
        donorRepository.findByUserIdForUpdate(donorUserId)
            .flatMap(donor -> bookingRepository.findOpenByDonorId(donor.getId()))
            .ifPresent(booking -> {
                Instant now = clock.instant();
                if (booking.getBookingDate().isBefore(LocalDate.now(clock))) {
                    booking.close(BookingStatus.MISSED, now);
                    bookingRepository.save(booking);
                    return;
                }
                booking.close(BookingStatus.CANCELLED, now);
                bookingRepository.save(booking);
                notificationService.notifyBookingCancelled(booking, BookingCancellation.ACCOUNT_SUSPENDED);
            });
    }

    /** The daily job: every open booking dated before today becomes MISSED. No notification. */
    @Transactional
    public int markMissed(Instant now) {
        return bookingRepository.markMissedBefore(LocalDate.ofInstant(now, clock.getZone()), now);
    }

    /**
     * The daily job: one reminder per open booking, to an ACTIVE donor: "tomorrow" for tomorrow's visits, "today" for
     * today's that never had one. The stamp makes it once per booking; a suspended donor is skipped, not stamped.
     */
    @Transactional
    public int sendDueReminders(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        return remind(today.plusDays(1), now, false) + remind(today, now, true);
    }

    /** Reminds every unreminded open booking on one date, winning each booking's stamp before writing its notice. */
    private int remind(LocalDate date, Instant now, boolean visitIsToday) {
        int sent = 0;
        for (DonationBooking booking : bookingRepository.findDueReminders(date)) {
            if (booking.getDonor().getUser().getStatus() != AccountStatus.ACTIVE) {
                continue;
            }
            if (bookingRepository.stampReminderSent(booking.getId(), now) == 1) {
                notificationService.sendBookingReminder(booking, now, visitIsToday);
                sent++;
            }
        }
        return sent;
    }

    /** today .. today + 30 inclusive, else a 400 on bookingDate. */
    private static void requireBookableDate(LocalDate date, LocalDate today) {
        if (date.isBefore(today)) {
            throw new InvalidFieldException("bookingDate", "The date cannot be in the past");
        }
        if (date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new InvalidFieldException("bookingDate", "Choose a date within the next " + MAX_DAYS_AHEAD + " days");
        }
    }

    /** The caller's donor row, write-locked; DONOR role checked before any read, a suspended account refused. */
    private Donor lockActiveDonor(AuthenticatedUser principal, String action) {
        requireDonorRole(principal, action);
        Donor donor = donorRepository.findByUserIdForUpdate(principal.getId())
            .orElseThrow(() -> new NotFoundException("No donor profile found for this account."));
        if (donor.getUser().getStatus() != AccountStatus.ACTIVE) {
            throw new AccessDeniedException("This account is suspended.");
        }
        return donor;
    }

    private static void requireDonorRole(AuthenticatedUser principal, String action) {
        if (principal.getRole() != Role.DONOR) {
            throw new AccessDeniedException("Only donors can " + action + ".");
        }
    }
}
