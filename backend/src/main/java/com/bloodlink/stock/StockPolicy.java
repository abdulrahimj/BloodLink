package com.bloodlink.stock;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The one definition of expired, usable and expiring soon for a batch, so every figure agrees.
 * Expiry is the last usable day inclusive; "today" is the UTC date from the injected Clock.
 */
@Component
public class StockPolicy {

    /** How far ahead "expiring soon" looks: 28 days (4 weeks), today included. */
    public static final int EXPIRING_SOON_WINDOW_DAYS = 28;

    /**
     * Default shelf life of donated units: donation date + 42 days (red cells in additive solution).
     * Provisional until confirmed with the blood bank staff.
     */
    public static final int DONATION_SHELF_LIFE_DAYS = 42;

    /**
     * The furthest an entered expiry may be: 365 days from today (today earliest), a typo guard
     * for Add New Batch and Record Donation.
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
     * What is wrong with a staff-entered expiry date, or empty: today to MAX_ENTERED_EXPIRY_DAYS away.
     * Returned rather than thrown so each caller attaches it to its own field.
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
