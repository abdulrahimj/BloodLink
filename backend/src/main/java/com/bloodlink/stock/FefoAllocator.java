package com.bloodlink.stock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Decides which batches a contribution takes from: first expiry first out, ties by id in PostgreSQL's uuid order
 * (so it matches the lock order). Expired or empty batches are never allocated, even if passed in.
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
     * Takes unitsWanted from the batches earliest-expiry first, the last one only partly if needed;
     * throws InsufficientUsableStockException, allocating nothing, if usable units fall short.
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
     * Compares ids as PostgreSQL orders uuid: high 64 bits unsigned, then low 64 bits unsigned.
     */
    static int compareAsPostgres(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
