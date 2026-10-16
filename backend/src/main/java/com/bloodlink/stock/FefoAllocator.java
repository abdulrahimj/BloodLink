package com.bloodlink.stock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Decides which batches a contribution to a hospital request takes its units from:
 * first expiry, first out (FEFO). The batch closest to its expiry date is used first,
 * so blood is issued before it can go to waste. Pure logic with no database access;
 * the contribution code (Stage 2) will lock the batches, pass them in, and apply the
 * result.
 *
 * <h2>The order</h2>
 * Earliest expiry date first; between batches expiring the same day, the smaller id
 * first. That tie-break compares ids exactly as PostgreSQL orders its uuid type
 * (byte by byte, unsigned), so the order batches are allocated in is the same order
 * the database locks them in ({@code ORDER BY expiry_date, id}). Java's own
 * {@link UUID#compareTo} compares signed numbers and would disagree for some ids.
 *
 * <h2>What is never allocated</h2>
 * An expired batch, or one with no units left, is skipped even if it is passed in.
 * The caller is meant to pass only usable batches; this is the second line of
 * defence, so expired blood cannot be issued through a mistake in a query.
 */
public final class FefoAllocator {

    /** One batch offered for allocation: what the allocator needs to know about it. */
    public record Candidate(UUID batchId, LocalDate expiryDate, int unitsRemaining) {}

    /** Units to take from one batch. */
    public record Allocation(UUID batchId, int units) {}

    /** Refused: the usable units offered do not cover the request. Says how short. */
    public static final class InsufficientUsableStockException extends RuntimeException {

        private final int unitsRequested;
        private final int usableUnits;
        private final int expiredUnits;

        InsufficientUsableStockException(int unitsRequested, int usableUnits, int expiredUnits) {
            super("Requested " + unitsRequested + " units but only " + usableUnits + " usable units are available"
                + (expiredUnits > 0 ? " (" + expiredUnits + " more are expired and cannot be issued)" : "") + ".");
            this.unitsRequested = unitsRequested;
            this.usableUnits = usableUnits;
            this.expiredUnits = expiredUnits;
        }

        public int getUnitsRequested() {
            return unitsRequested;
        }

        public int getUsableUnits() {
            return usableUnits;
        }

        /** Units in the batches passed in that were expired, and so not counted. */
        public int getExpiredUnits() {
            return expiredUnits;
        }
    }

    /** The issue order: earliest expiry, then id in PostgreSQL's uuid order. */
    public static final Comparator<Candidate> ISSUE_ORDER = Comparator
        .comparing(Candidate::expiryDate)
        .thenComparing(Candidate::batchId, FefoAllocator::compareAsPostgres);

    private FefoAllocator() {
        // Utility class.
    }

    /**
     * Takes {@code unitsWanted} units from the batches, earliest expiry first, whole
     * batches before the next one is touched, and the last batch only partly if that
     * is all that is needed.
     *
     * @param unitsWanted how many units the contribution issues; must be positive
     * @param candidates  the batches on offer, in any order; the list is not changed
     * @param today       today's UTC date, which decides what is expired
     * @return what to take from which batch, in issue order; always adds up to
     *         exactly {@code unitsWanted}
     * @throws InsufficientUsableStockException if the usable units do not cover it,
     *         in which case nothing is allocated at all
     */
    public static List<Allocation> allocate(int unitsWanted, List<Candidate> candidates, LocalDate today) {
        if (unitsWanted <= 0) {
            throw new IllegalArgumentException("Units wanted must be positive.");
        }

        List<Candidate> usable = new ArrayList<>();
        int usableUnits = 0;
        int expiredUnits = 0;
        for (Candidate candidate : candidates) {
            if (candidate.unitsRemaining() <= 0) {
                continue;
            }
            if (StockPolicy.isExpired(candidate.expiryDate(), today)) {
                expiredUnits += candidate.unitsRemaining();
                continue;
            }
            usable.add(candidate);
            usableUnits += candidate.unitsRemaining();
        }

        // All or nothing: a partial allocation would issue fewer units than the
        // operator asked for and leave them believing they had sent more.
        if (usableUnits < unitsWanted) {
            throw new InsufficientUsableStockException(unitsWanted, usableUnits, expiredUnits);
        }

        usable.sort(ISSUE_ORDER);

        List<Allocation> allocations = new ArrayList<>();
        int stillNeeded = unitsWanted;
        for (Candidate candidate : usable) {
            if (stillNeeded == 0) {
                break;
            }
            int take = Math.min(candidate.unitsRemaining(), stillNeeded);
            allocations.add(new Allocation(candidate.batchId(), take));
            stillNeeded -= take;
        }
        return List.copyOf(allocations);
    }

    /**
     * Compares two ids the way PostgreSQL orders the uuid type: the 16 bytes from the
     * first, each read as unsigned. That is the high 64 bits compared unsigned, then
     * the low 64 bits compared unsigned.
     */
    static int compareAsPostgres(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
