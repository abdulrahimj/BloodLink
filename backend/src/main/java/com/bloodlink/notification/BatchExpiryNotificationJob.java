package com.bloodlink.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.bloodlink.booking.DonationBookingService;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.notification.NotificationService.ExpiryNotices;
import com.bloodlink.repository.BloodBankRepository;

/**
 * The daily 06:00 UTC run (configurable): batch expiry notices (Business Rule 26) and donation bookings (Rule 29),
 * and once at startup if that time has already passed today, so a server that was off at 06:00 still runs it.
 */
@Component
@ConditionalOnProperty(name = "bloodlink.expiry-notifications.enabled", havingValue = "true", matchIfMissing = true)
public class BatchExpiryNotificationJob {

    /** Every day at 06:00, read in UTC: second, minute, hour, day of month, month, day of week. */
    public static final String DEFAULT_CRON = "0 0 6 * * *";

    private static final Logger log = LoggerFactory.getLogger(BatchExpiryNotificationJob.class);

    private final NotificationService notificationService;
    private final BloodBankRepository bloodBankRepository;
    private final DonationBookingService donationBookingService;
    private final Clock clock;
    private final CronExpression schedule;

    // Public so the integration test can build one while the scheduled bean is switched off.
    public BatchExpiryNotificationJob(
        NotificationService notificationService,
        BloodBankRepository bloodBankRepository,
        DonationBookingService donationBookingService,
        Clock clock,
        @Value("${bloodlink.expiry-notifications.cron:" + DEFAULT_CRON + "}") String cron
    ) {
        this.notificationService = notificationService;
        this.bloodBankRepository = bloodBankRepository;
        this.donationBookingService = donationBookingService;
        this.clock = clock;
        this.schedule = CronExpression.parse(cron);
    }

    /** The daily run. Spring's scheduler decides when; the date judged always comes from the injected Clock. */
    @Scheduled(cron = "${bloodlink.expiry-notifications.cron:" + DEFAULT_CRON + "}", zone = "UTC")
    public void runScheduled() {
        runForAllBanks();
        runBookings();
    }

    /** Catch-up at startup when today's time has passed; harmless if today's run already happened. */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (scheduledTimePassedToday(clock.instant())) {
            runForAllBanks();
            runBookings();
        }
    }

    /**
     * Donation bookings (Business Rule 29): past open ones become MISSED, then tomorrow's get their one reminder.
     * Each step is its own transaction, so one failing does not stop the other; both are safe to repeat.
     */
    public void runBookings() {
        Instant now = clock.instant();
        try {
            int missed = donationBookingService.markMissed(now);
            if (missed > 0) {
                log.info("Marked {} donation booking(s) as missed.", missed);
            }
        } catch (RuntimeException ex) {
            log.error("Marking missed donation bookings failed; reminders still run.", ex);
        }
        try {
            int reminded = donationBookingService.sendDueReminders(now);
            if (reminded > 0) {
                log.info("Sent {} donation booking reminder(s).", reminded);
            }
        } catch (RuntimeException ex) {
            log.error("Sending donation booking reminders failed.", ex);
        }
    }

    /** True once the schedule's first time today (UTC) is at or before {@code now}. */
    boolean scheduledTimePassedToday(Instant now) {
        ZonedDateTime midnight = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime firstToday = schedule.next(midnight.minusNanos(1));
        return firstToday != null && firstToday.isBefore(midnight.plusDays(1)) && !firstToday.toInstant().isAfter(now);
    }

    /** Every bank, each in its own transaction, so a failure at one bank does not stop the others. */
    public void runForAllBanks() {
        Instant now = clock.instant();
        for (BloodBank bank : bloodBankRepository.findAll()) {
            try {
                ExpiryNotices sent = notificationService.notifyBatchExpiry(bank.getId(), now);
                if (sent.remindedBatches() > 0 || sent.expiredBatches() > 0) {
                    log.info("Expiry notices for {}: {} batch(es) in the 7-day reminder, {} expired batch(es).",
                        bank.getName(), sent.remindedBatches(), sent.expiredBatches());
                }
            } catch (RuntimeException ex) {
                log.error("Daily expiry notices failed for blood bank {}; the other banks continue.", bank.getId(), ex);
            }
        }
    }
}
