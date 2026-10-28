package com.bloodlink.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.booking.dto.BookingResponse;
import com.bloodlink.booking.dto.CreateBookingPayload;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.DonationBooking;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
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
 * Business Rule 29's decisions with no database: who may book, which dates, one open booking, cancelling,
 * what a donation does to a booking, and the daily job's reminder stamp. Today is fixed at 28 Oct 2026 (UTC).
 */
@ExtendWith(MockitoExtension.class)
class DonationBookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private DonationBookingRepository bookingRepository;
    @Mock private DonorRepository donorRepository;
    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private ActingBankResolver actingBankResolver;
    @Mock private NotificationService notificationService;

    private DonationBookingService service;
    private BloodBank bank;

    @BeforeEach
    void setUp() {
        service = new DonationBookingService(bookingRepository, donorRepository, bloodBankRepository,
            actingBankResolver, notificationService, Clock.fixed(NOW, ZoneOffset.UTC));
        bank = bank("Makeni Regional Blood Bank");
    }

    // ---------- fixtures ----------

    private static BloodBank bank(String name) {
        BloodBank created = new BloodBank();
        created.setId(UUID.randomUUID());
        created.setName(name);
        return created;
    }

    private static Donor donor(LocalDate lastDonation, AccountStatus status) {
        User user = new User();
        user.setId(USER_ID);
        user.setFullName("Aminata Kamara");
        user.setStatus(status);
        user.setCreatedAt(NOW);
        Donor donor = new Donor();
        donor.setId(UUID.randomUUID());
        donor.setUser(user);
        donor.setLastDonationDate(lastDonation);
        return donor;
    }

    private static AuthenticatedUser donorPrincipal() {
        return new AuthenticatedUser(USER_ID, "+23276000001", "hash", Role.DONOR);
    }

    private static DonationBooking booking(Donor donor, BloodBank at, LocalDate date) {
        DonationBooking booking = new DonationBooking();
        booking.setId(UUID.randomUUID());
        booking.setDonor(donor);
        booking.setBloodBank(at);
        booking.setBookingDate(date);
        booking.setTimeOfDay(BookingTimeOfDay.MORNING);
        return booking;
    }

    private Donor stubDonor(LocalDate lastDonation, AccountStatus status) {
        Donor donor = donor(lastDonation, status);
        when(donorRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(donor));
        return donor;
    }

    private void stubSaving() {
        when(bloodBankRepository.findById(bank.getId())).thenReturn(Optional.of(bank));
        when(bookingRepository.save(any(DonationBooking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateBookingPayload payload(LocalDate date) {
        return new CreateBookingPayload(bank.getId(), date, "AFTERNOON");
    }

    // ---------- booking ----------

    @Test
    void anEligibleActiveDonorBooksAndTheBankIsTold() {
        stubDonor(null, AccountStatus.ACTIVE);
        stubSaving();

        BookingResponse response = service.book(payload(TODAY.plusDays(3)), donorPrincipal());

        assertThat(response.bloodBankName()).isEqualTo("Makeni Regional Blood Bank");
        assertThat(response.bookingDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(response.timeOfDay()).isEqualTo(BookingTimeOfDay.AFTERNOON);
        assertThat(response.status()).isEqualTo(BookingStatus.BOOKED);
        assertThat(response.createdAt()).isEqualTo(NOW);
        verify(notificationService).notifyBookingMade(any(DonationBooking.class));
    }

    @Test
    void todayAndThirtyDaysAheadAreTheLimitsBothIncluded() {
        stubDonor(null, AccountStatus.ACTIVE);
        stubSaving();

        assertThat(service.book(payload(TODAY), donorPrincipal()).bookingDate()).isEqualTo(TODAY);
        assertThat(service.book(payload(TODAY.plusDays(30)), donorPrincipal()).bookingDate()).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    void yesterdayAndThirtyOneDaysAheadAreRefusedOnTheDateField() {
        stubDonor(null, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> service.book(payload(TODAY.minusDays(1)), donorPrincipal()))
            .isInstanceOf(InvalidFieldException.class).hasMessage("The date cannot be in the past")
            .extracting(e -> ((InvalidFieldException) e).getField()).isEqualTo("bookingDate");
        assertThatThrownBy(() -> service.book(payload(TODAY.plusDays(31)), donorPrincipal()))
            .isInstanceOf(InvalidFieldException.class).hasMessage("Choose a date within the next 30 days");
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void aDonorInRecoveryIsRefusedWithTheDateTheyMayBookFrom() {
        stubDonor(LocalDate.now().minusDays(10), AccountStatus.ACTIVE);

        assertThatThrownBy(() -> service.book(payload(TODAY.plusDays(1)), donorPrincipal()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("You can book a donation from")
            .hasMessageContaining("90 days");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aSuspendedDonorIsRefused() {
        stubDonor(null, AccountStatus.SUSPENDED);

        assertThatThrownBy(() -> service.book(payload(TODAY.plusDays(1)), donorPrincipal()))
            .isInstanceOf(AccessDeniedException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void anyoneButADonorIsRefusedBeforeAnythingIsRead() {
        AuthenticatedUser staff = new AuthenticatedUser(USER_ID, "+23276000002", "hash", Role.BLOOD_BANK_STAFF);

        assertThatThrownBy(() -> service.book(payload(TODAY.plusDays(1)), staff))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(donorRepository, bookingRepository, notificationService);
    }

    @Test
    void aSecondOpenBookingIsRefused() {
        Donor donor = stubDonor(null, AccountStatus.ACTIVE);
        when(bookingRepository.findOpenByDonorId(donor.getId()))
            .thenReturn(Optional.of(booking(donor, bank, TODAY.plusDays(2))));

        assertThatThrownBy(() -> service.book(payload(TODAY.plusDays(5)), donorPrincipal()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("You already have a donation booked for 30 October 2026, morning at Makeni");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void anOpenBookingWhoseDateHasPassedIsMissedNotInTheWay() {
        Donor donor = stubDonor(null, AccountStatus.ACTIVE);
        DonationBooking stale = booking(donor, bank, TODAY.minusDays(1));
        when(bookingRepository.findOpenByDonorId(donor.getId())).thenReturn(Optional.of(stale));
        stubSaving();

        service.book(payload(TODAY.plusDays(1)), donorPrincipal());

        assertThat(stale.getStatus()).isEqualTo(BookingStatus.MISSED);
        assertThat(stale.getClosedAt()).isEqualTo(NOW);
        verify(bookingRepository).saveAndFlush(stale);
    }

    // ---------- cancelling ----------

    @Test
    void theDonorCancelsTheirOpenBookingAndTheBankIsTold() {
        Donor donor = stubDonor(null, AccountStatus.ACTIVE);
        DonationBooking open = booking(donor, bank, TODAY);
        when(bookingRepository.findOpenByDonorId(donor.getId())).thenReturn(Optional.of(open));

        BookingResponse response = service.cancelMine(donorPrincipal());

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(open.getClosedAt()).isEqualTo(NOW);
        verify(notificationService).notifyBookingCancelled(open, BookingCancellation.BY_DONOR);
    }

    @Test
    void aBookingWhoseDateHasPassedCannotBeCancelled() {
        Donor donor = stubDonor(null, AccountStatus.ACTIVE);
        DonationBooking past = booking(donor, bank, TODAY.minusDays(1));
        when(bookingRepository.findOpenByDonorId(donor.getId())).thenReturn(Optional.of(past));

        assertThatThrownBy(() -> service.cancelMine(donorPrincipal())).isInstanceOf(ConflictException.class);
        assertThat(past.getStatus()).isEqualTo(BookingStatus.BOOKED);
        verifyNoInteractions(notificationService);
    }

    @Test
    void nothingToCancelIsA404() {
        stubDonor(null, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> service.cancelMine(donorPrincipal())).isInstanceOf(NotFoundException.class);
    }

    // ---------- Record Donation closes the booking ----------

    @Test
    void aDonationAtTheBookedBankAttendsTheBookingAndLinksIt() {
        Donor donor = donor(null, AccountStatus.ACTIVE);
        DonationBooking open = booking(donor, bank, TODAY);
        when(bookingRepository.findOpenByDonorId(donor.getId())).thenReturn(Optional.of(open));
        Donation donation = new Donation();

        service.closeOnDonation(donor, donation, bank);

        assertThat(open.getStatus()).isEqualTo(BookingStatus.ATTENDED);
        assertThat(open.getDonation()).isSameAs(donation);
        assertThat(open.getClosedAt()).isEqualTo(NOW);
        verifyNoInteractions(notificationService);
    }

    @Test
    void aDonationAtAnotherBankCancelsTheBookingAndTellsTheBookedBank() {
        Donor donor = donor(null, AccountStatus.ACTIVE);
        DonationBooking open = booking(donor, bank, TODAY.plusDays(4));
        when(bookingRepository.findOpenByDonorId(donor.getId())).thenReturn(Optional.of(open));

        service.closeOnDonation(donor, new Donation(), bank("Holy Spirit Blood Bank"));

        assertThat(open.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(open.getDonation()).isNull();
        verify(notificationService).notifyBookingCancelled(open, BookingCancellation.DONATED_ELSEWHERE);
    }

    @Test
    void aDonationWithNoOpenBookingChangesNothing() {
        Donor donor = donor(null, AccountStatus.ACTIVE);

        service.closeOnDonation(donor, new Donation(), bank);

        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    // ---------- the daily job's reminder ----------

    @Test
    void remindersGoOutForTomorrowOnlyWhenThisRunWinsTheStampAndNeverToASuspendedDonor() {
        DonationBooking won = booking(donor(null, AccountStatus.ACTIVE), bank, TODAY.plusDays(1));
        DonationBooking alreadyStamped = booking(donor(null, AccountStatus.ACTIVE), bank, TODAY.plusDays(1));
        DonationBooking suspended = booking(donor(null, AccountStatus.SUSPENDED), bank, TODAY.plusDays(1));
        when(bookingRepository.findDueReminders(TODAY.plusDays(1))).thenReturn(List.of(won, alreadyStamped, suspended));
        when(bookingRepository.stampReminderSent(won.getId(), NOW)).thenReturn(1);
        when(bookingRepository.stampReminderSent(alreadyStamped.getId(), NOW)).thenReturn(0);

        assertThat(service.sendDueReminders(NOW)).isEqualTo(1);

        verify(notificationService).sendBookingReminder(won, NOW, false);
        verify(notificationService, never()).sendBookingReminder(eq(alreadyStamped), any(), anyBoolean());
        verify(bookingRepository, never()).stampReminderSent(eq(suspended.getId()), any());
    }

    @Test
    void missedIsEveryOpenBookingBeforeToday() {
        when(bookingRepository.markMissedBefore(TODAY, NOW)).thenReturn(3);

        assertThat(service.markMissed(NOW)).isEqualTo(3);
    }

    // ---------- the staff list ----------

    @Test
    void theStaffListAsksTheBankRuleFirstAndReadsOnlyThatBanksUpcomingVisits() {
        AuthenticatedUser staff = new AuthenticatedUser(USER_ID, "+23276000002", "hash", Role.BLOOD_BANK_STAFF);
        when(actingBankResolver.resolveForStockChange(eq(staff), any())).thenReturn(bank);
        Donor typed = donor(null, AccountStatus.ACTIVE);
        typed.setBloodType(com.bloodlink.domain.enums.BloodType.O_NEGATIVE);
        when(bookingRepository.findUpcomingForBank(bank.getId(), TODAY))
            .thenReturn(List.of(booking(typed, bank, TODAY), booking(donor(null, AccountStatus.ACTIVE), bank, TODAY)));

        var rows = service.upcomingForMyBank(staff);

        assertThat(rows).extracting(r -> r.bloodType()).containsExactly("O-", null);
        assertThat(rows.get(0).donorName()).isEqualTo("Aminata Kamara");
    }

    @Test
    void theStaffListRefusedByTheBankRuleReadsNothing() {
        AuthenticatedUser admin = new AuthenticatedUser(USER_ID, "+23276000009", "hash", Role.ADMIN);
        when(actingBankResolver.resolveForStockChange(eq(admin), any()))
            .thenThrow(new AccessDeniedException("Only the blood bank's own staff can view this bank's donation visits."));

        assertThatThrownBy(() -> service.upcomingForMyBank(admin)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void theSavedBookingCarriesWhatTheDonorChose() {
        stubDonor(null, AccountStatus.ACTIVE);
        stubSaving();

        service.book(payload(TODAY.plusDays(2)), donorPrincipal());

        ArgumentCaptor<DonationBooking> saved = ArgumentCaptor.forClass(DonationBooking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getBloodBank()).isSameAs(bank);
        assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.BOOKED);
        assertThat(saved.getValue().getTimeOfDay()).isEqualTo(BookingTimeOfDay.AFTERNOON);
    }

    // ---------- the "today" reminder and suspension ----------

    @Test
    void aVisitTodayThatNeverHadAReminderGetsTheTodayOne() {
        DonationBooking bookedLate = booking(donor(null, AccountStatus.ACTIVE), bank, TODAY);
        when(bookingRepository.findDueReminders(TODAY.plusDays(1))).thenReturn(List.of());
        when(bookingRepository.findDueReminders(TODAY)).thenReturn(List.of(bookedLate));
        when(bookingRepository.stampReminderSent(bookedLate.getId(), NOW)).thenReturn(1);

        assertThat(service.sendDueReminders(NOW)).isEqualTo(1);

        verify(notificationService).sendBookingReminder(bookedLate, NOW, true);
    }

    @Test
    void suspendingADonorCancelsTheirOpenVisitAndTellsTheBank() {
        Donor donor = stubDonor(null, AccountStatus.SUSPENDED);
        DonationBooking open = booking(donor, bank, TODAY.plusDays(2));
        when(bookingRepository.findOpenByDonorId(donor.getId())).thenReturn(Optional.of(open));

        service.cancelOnSuspension(USER_ID);

        assertThat(open.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(open.getClosedAt()).isEqualTo(NOW);
        verify(notificationService).notifyBookingCancelled(open, BookingCancellation.ACCOUNT_SUSPENDED);
    }

    @Test
    void suspendingADonorWithNoOpenVisitChangesNothingAndAPastOneIsSimplyMissed() {
        Donor without = stubDonor(null, AccountStatus.SUSPENDED);
        service.cancelOnSuspension(USER_ID);
        verify(bookingRepository, never()).save(any());

        DonationBooking past = booking(without, bank, TODAY.minusDays(1));
        when(bookingRepository.findOpenByDonorId(without.getId())).thenReturn(Optional.of(past));
        service.cancelOnSuspension(USER_ID);

        assertThat(past.getStatus()).isEqualTo(BookingStatus.MISSED);
        verifyNoInteractions(notificationService);
    }
}
