package com.bloodlink.stock;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The one definition of what "expired", "usable" and "expiring soon" mean for a
 * batch of stock — the batch-stock redesign. Every stock figure the application
 * shows will be built on these answers (from Stage 2), so the Inventory screen, the
 * dashboard, hospital availability, alerts, appeals and the Admin Reports cannot
 * disagree about which units count. The frontend will be sent the result rather
 * than working it out again.
 *
 * <h2>The rule</h2>
 * A batch's expiry date is the last day it may be used, <b>inclusive</b>: it is
 * usable through the whole of that date and expired from 00:00 UTC the next day.
 * <ul>
 *   <li><b>Expired</b>: expiry date before today.</li>
 *   <li><b>Usable</b>: expiry date today or later. (Whether any units are left is a
 *       separate question about the batch, not about its date.)</li>
 *   <li><b>Expiring soon</b>: usable, and expiring within
 *       {@link #EXPIRING_SOON_WINDOW_DAYS} days, today included.</li>
 * </ul>
 * "Today" is the UTC date from the injected {@link Clock} (see TimeConfig), which
 * is what makes the midnight boundary testable.
 *
 * Each rule comes in two forms: a static one that takes "today" explicitly, for
 * pure logic and tests, and an instance one that reads today from the clock.
 */
@Component
public class StockPolicy {

    /** How far ahead "expiring soon" looks: 28 days (4 weeks), today included. */
    public static final int EXPIRING_SOON_WINDOW_DAYS = 28;

    /**
     * The default shelf life given to units from a recorded donation: expiry =
     * donation date + 42 days, the usual life of red cells in additive solution.
     * PROVISIONAL: the project owner is confirming the real value with the blood
     * bank staff, and staff will be able to edit the date when recording.
     */
    public static final int DONATION_SHELF_LIFE_DAYS = 42;

    /**
     * The furthest ahead an expiry date entered by staff may be: 365 days from today,
     * today itself being the earliest. A typo guard (a year typed as 2027 instead of
     * 2026 would otherwise make a batch look good for a year), agreed with the project
     * owner. Applies to Add New Batch and to Record Donation's optional expiry date.
     */
    public static final int MAX_ENTERED_EXPIRY_DAYS = 365;

    /** Where a batch stands on a given day. EXPIRING_SOON is still usable. */
    public enum ExpiryState {
        USABLE,
        EXPIRING_SOON,
        EXPIRED;

        /** True for USABLE and EXPIRING_SOON: the batch may be issued. */
        public boolean isUsable() {
            return this != EXPIRED;
        }
    }

    private final Clock clock;

    public StockPolicy(Clock clock) {
        this.clock = clock;
    }

    /** Today's date in UTC, from the clock. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------- the rules, with "today" given ----------

    /** Expired: the expiry date is before today. A batch expiring today is not expired. */
    public static boolean isExpired(LocalDate expiryDate, LocalDate today) {
        return expiryDate.isBefore(today);
    }

    /** Usable: the expiry date is today or later. */
    public static boolean isUsable(LocalDate expiryDate, LocalDate today) {
        return !isExpired(expiryDate, today);
    }

    /** The last expiry date that counts as expiring soon: today + 28 days. */
    public static LocalDate expiringSoonCutoff(LocalDate today) {
        return today.plusDays(EXPIRING_SOON_WINDOW_DAYS);
    }

    /** Expiring soon: usable, and expiring on or before today + 28 days. */
    public static boolean isExpiringSoon(LocalDate expiryDate, LocalDate today) {
        return isUsable(expiryDate, today) && !expiryDate.isAfter(expiringSoonCutoff(today));
    }

    /** The three-way answer the screens will show. */
    public static ExpiryState stateOf(LocalDate expiryDate, LocalDate today) {
        if (isExpired(expiryDate, today)) {
            return ExpiryState.EXPIRED;
        }
        return isExpiringSoon(expiryDate, today) ? ExpiryState.EXPIRING_SOON : ExpiryState.USABLE;
    }

    /** Days from today to the expiry date: 0 on the expiry day itself, negative once expired. */
    public static long daysUntilExpiry(LocalDate expiryDate, LocalDate today) {
        return ChronoUnit.DAYS.between(today, expiryDate);
    }

    /** The default expiry for units from a donation made on {@code donationDate}. */
    public static LocalDate defaultDonationExpiry(LocalDate donationDate) {
        return donationDate.plusDays(DONATION_SHELF_LIFE_DAYS);
    }

    /**
     * What is wrong with an expiry date typed by staff, or empty if it is acceptable:
     * it must be today or later, and at most {@link #MAX_ENTERED_EXPIRY_DAYS} days
     * away. Returned as a message rather than thrown, so each caller can attach it to
     * its own request field.
     */
    public static Optional<String> problemWithEnteredExpiry(LocalDate expiryDate, LocalDate today) {
        if (expiryDate.isBefore(today)) {
            return Optional.of("Expiry date cannot be in the past");
        }
        LocalDate latest = today.plusDays(MAX_ENTERED_EXPIRY_DAYS);
        if (expiryDate.isAfter(latest)) {
            return Optional.of("Expiry date must be within " + MAX_ENTERED_EXPIRY_DAYS
                + " days of today (on or before " + latest + ") — please check the year");
        }
        return Optional.empty();
    }

    // ---------- the same rules, with today from the clock ----------

    public boolean isExpired(LocalDate expiryDate) {
        return isExpired(expiryDate, today());
    }

    public boolean isUsable(LocalDate expiryDate) {
        return isUsable(expiryDate, today());
    }

    public boolean isExpiringSoon(LocalDate expiryDate) {
        return isExpiringSoon(expiryDate, today());
    }

    public ExpiryState stateOf(LocalDate expiryDate) {
        return stateOf(expiryDate, today());
    }

    public long daysUntilExpiry(LocalDate expiryDate) {
        return daysUntilExpiry(expiryDate, today());
    }

    public Optional<String> problemWithEnteredExpiry(LocalDate expiryDate) {
        return problemWithEnteredExpiry(expiryDate, today());
    }
}
