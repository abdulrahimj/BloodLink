package com.bloodlink.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.booking.DonationBookingService;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.notification.NotificationService.ExpiryNotices;
import com.bloodlink.repository.BloodBankRepository;

/**
 * The daily job's own decisions (Business Rules 26, 29): when the startup catch-up runs, that the date comes
 * from the injected Clock, and that one bank failing does not stop the rest. What is sent: BatchExpiryNotificationIT.
 */
@ExtendWith(MockitoExtension.class)
class BatchExpiryNotificationJobTest {

    @Mock private NotificationService notificationService;
    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private DonationBookingService donationBookingService;

    private BatchExpiryNotificationJob jobAt(String instant, String cron) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new BatchExpiryNotificationJob(notificationService, bloodBankRepository, donationBookingService, clock, cron);
    }

    private static BloodBank bank(String name) {
        BloodBank bank = new BloodBank();
        bank.setId(UUID.randomUUID());
        bank.setName(name);
        return bank;
    }

    @Test
    void todaysRunCountsAsDueFromSixOClockUtcOnwards() {
        BatchExpiryNotificationJob job = jobAt("2026-10-24T12:00:00Z", BatchExpiryNotificationJob.DEFAULT_CRON);

        assertThat(job.scheduledTimePassedToday(Instant.parse("2026-10-24T05:59:59Z"))).isFalse();
        assertThat(job.scheduledTimePassedToday(Instant.parse("2026-10-24T06:00:00Z"))).isTrue();
        assertThat(job.scheduledTimePassedToday(Instant.parse("2026-10-24T23:59:59Z"))).isTrue();
        assertThat(job.scheduledTimePassedToday(Instant.parse("2026-10-25T00:00:00Z"))).isFalse();
    }

    @Test
    void aConfiguredTimeIsHonoured() {
        BatchExpiryNotificationJob job = jobAt("2026-10-24T12:00:00Z", "0 30 14 * * *");

        assertThat(job.scheduledTimePassedToday(Instant.parse("2026-10-24T14:29:59Z"))).isFalse();
        assertThat(job.scheduledTimePassedToday(Instant.parse("2026-10-24T14:30:00Z"))).isTrue();
    }

    @Test
    void startingBeforeTodaysTimeSendsNothingStartingAfterItCatchesUp() {
        jobAt("2026-10-24T05:00:00Z", BatchExpiryNotificationJob.DEFAULT_CRON).catchUpOnStartup();
        verifyNoInteractions(notificationService, bloodBankRepository, donationBookingService);

        BloodBank bank = bank("Makeni Regional Blood Bank");
        when(bloodBankRepository.findAll()).thenReturn(List.of(bank));
        when(notificationService.notifyBatchExpiry(any(), any())).thenReturn(new ExpiryNotices(0, 0));

        jobAt("2026-10-24T09:00:00Z", BatchExpiryNotificationJob.DEFAULT_CRON).catchUpOnStartup();
        verify(notificationService).notifyBatchExpiry(bank.getId(), Instant.parse("2026-10-24T09:00:00Z"));
    }

    @Test
    void oneBankFailingDoesNotStopTheOthers() {
        BloodBank broken = bank("Broken Bank");
        BloodBank fine = bank("Fine Bank");
        Instant now = Instant.parse("2026-10-24T06:00:00Z");
        when(bloodBankRepository.findAll()).thenReturn(List.of(broken, fine));
        when(notificationService.notifyBatchExpiry(broken.getId(), now)).thenThrow(new IllegalStateException("boom"));
        when(notificationService.notifyBatchExpiry(fine.getId(), now)).thenReturn(new ExpiryNotices(1, 2));

        jobAt("2026-10-24T06:00:00Z", BatchExpiryNotificationJob.DEFAULT_CRON).runScheduled();

        // Both banks are tried, each with the clock's instant, never the system time.
        verify(notificationService).notifyBatchExpiry(broken.getId(), now);
        verify(notificationService).notifyBatchExpiry(fine.getId(), now);
    }

    // ---------- donation bookings (Business Rule 29) ----------

    @Test
    void theDailyRunAndTheCatchUpBothCloseMissedBookingsAndSendRemindersAtTheClocksInstant() {
        when(bloodBankRepository.findAll()).thenReturn(List.of());
        Instant scheduled = Instant.parse("2026-10-24T06:00:00Z");
        jobAt("2026-10-24T06:00:00Z", BatchExpiryNotificationJob.DEFAULT_CRON).runScheduled();
        verify(donationBookingService).markMissed(scheduled);
        verify(donationBookingService).sendDueReminders(scheduled);

        Instant late = Instant.parse("2026-10-24T09:00:00Z");
        jobAt("2026-10-24T09:00:00Z", BatchExpiryNotificationJob.DEFAULT_CRON).catchUpOnStartup();
        verify(donationBookingService).markMissed(late);
        verify(donationBookingService).sendDueReminders(late);
    }

    @Test
    void markingMissedBookingsFailingStillSendsTheReminders() {
        when(bloodBankRepository.findAll()).thenReturn(List.of());
        Instant now = Instant.parse("2026-10-24T06:00:00Z");
        when(donationBookingService.markMissed(now)).thenThrow(new IllegalStateException("boom"));

        jobAt("2026-10-24T06:00:00Z", BatchExpiryNotificationJob.DEFAULT_CRON).runScheduled();

        verify(donationBookingService).sendDueReminders(now);
    }
}
