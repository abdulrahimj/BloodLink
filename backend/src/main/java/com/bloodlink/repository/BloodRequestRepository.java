package com.bloodlink.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.UrgencyLevel;

import jakarta.persistence.LockModeType;

public interface BloodRequestRepository extends JpaRepository<BloodRequest, UUID> {

    /**
     * "My Recent Requests" on the Hospital Portal — this hospital's own requests
     * only, newest first. A hospital may never see another hospital's requests, so
     * the scoping is in the query rather than filtered after the fact.
     */
    List<BloodRequest> findByHospitalIdOrderByCreatedAtDesc(UUID hospitalId);

    /**
     * Every request's status, counted — the aggregate behind the Admin Console's
     * "Request Outcomes" chart, citywide across every hospital and bank.
     *
     * One grouped query rather than four separate counts (one per RequestStatus),
     * because the four figures are always shown together and a single round trip
     * is honest about that: there is no scenario where a caller wants "Fulfilled"
     * without also wanting the other three to compare it against.
     */
    @Query("select r.status as status, count(r) as total from BloodRequest r group by r.status")
    List<StatusCount> countGroupedByStatus();

    /** One status's count, from {@link #countGroupedByStatus}. */
    interface StatusCount {
        RequestStatus getStatus();
        long getTotal();
    }

    /**
     * "Incoming Hospital Requests" for one blood bank: every request this bank can
     * still contribute to, plus every request it has already contributed to, newest
     * first — minus anything it has declined.
     *
     * The first half of the OR is Business Rule 20's central reversal. A request
     * with units still outstanding — PENDING, or PARTIALLY_FULFILLED because some
     * other bank has covered part of it — stays visible to *every* bank, because
     * every bank can still help with the remainder. Under the old model a bank's
     * approval claimed the request exclusively and it vanished from every other
     * queue, which is exactly what made a 4-unit request unfillable by two banks
     * holding 2 units each.
     *
     * The second half keeps a bank's own history in front of it: a request it has
     * contributed to stays in its queue after it closes, so the operator can see
     * that it completed rather than watching it disappear the moment the last unit
     * arrives from somewhere else. A request another bank completed, that this bank
     * never touched, is not this bank's business and does not appear.
     *
     * The not-exists clause is Business Rule 11's per-bank half, unchanged: a
     * decline hides the request from the declining bank's queue alone, never from
     * anyone else's.
     */
    @Query("""
        select r from BloodRequest r
        where (r.status in (com.bloodlink.domain.enums.RequestStatus.PENDING,
                            com.bloodlink.domain.enums.RequestStatus.PARTIALLY_FULFILLED)
               or exists (
                   select 1 from BloodRequestContribution c
                   where c.bloodRequest.id = r.id and c.bloodBank.id = :bloodBankId
               ))
          and not exists (
              select 1 from BloodRequestDecline d
              where d.bloodRequest.id = r.id and d.bloodBank.id = :bloodBankId
          )
        order by r.createdAt desc
        """)
    List<BloodRequest> findActionableForBank(@Param("bloodBankId") UUID bloodBankId);

    /** Every request, newest first — the ADMIN's citywide view. */
    List<BloodRequest> findAllByOrderByCreatedAtDesc();

    /**
     * The "Open Requests" stat card: requests this bank could contribute to right
     * now — still short of units, and not declined by this bank.
     *
     * Counted over the same scope {@link #findActionableForBank} lists, minus the
     * already-closed requests that query keeps for history, so the card never shows
     * a number larger than the set of rows underneath it that still have a
     * Contribute button. PARTIALLY_FULFILLED counts here as squarely as PENDING:
     * both mean units are still needed.
     */
    @Query("""
        select count(r) from BloodRequest r
        where r.status in (com.bloodlink.domain.enums.RequestStatus.PENDING,
                           com.bloodlink.domain.enums.RequestStatus.PARTIALLY_FULFILLED)
          and not exists (
              select 1 from BloodRequestDecline d
              where d.bloodRequest.id = r.id and d.bloodBank.id = :bloodBankId
          )
        """)
    long countOpenForBank(@Param("bloodBankId") UUID bloodBankId);

    /**
     * Loads one request with a row-level write lock, for the contribute and decline
     * transitions in RequestService.
     *
     * This is the whole concurrency guarantee behind Business Rule 20, and it
     * matters more now than it did when a request was claimed by one bank. Every
     * bank sees every open request, so two banks contributing to the same one at
     * the same moment is an ordinary event. Each transaction reads the units
     * contributed so far and subtracts from the request's total — separate
     * statements — so without the lock both would read the same "2 already given",
     * both would believe 2 were still needed, and a 4-unit request would be covered
     * by 6 units of real blood. Locking the request row first serialises them: the
     * second transaction waits, then reads a total that includes the first
     * contribution, and its own units are capped against what is genuinely left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BloodRequest r where r.id = :id")
    Optional<BloodRequest> findByIdForUpdate(@Param("id") UUID id);

    // ---------- Admin Reports: requests in a date range (Business Rule 23) ----------
    // "In the range" means raised (created_at) in [from, to), both UTC instants.

    @Query("""
        select r.status as status, count(r) as total from BloodRequest r
        where r.createdAt >= :from and r.createdAt < :to
        group by r.status
        """)
    List<StatusCount> countByStatusCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
        select r.bloodType as bloodType, count(r) as total from BloodRequest r
        where r.createdAt >= :from and r.createdAt < :to
        group by r.bloodType
        """)
    List<BloodTypeCount> countByBloodTypeCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
        select r.urgencyLevel as urgency, count(r) as total from BloodRequest r
        where r.createdAt >= :from and r.createdAt < :to
        group by r.urgencyLevel
        """)
    List<UrgencyCount> countByUrgencyCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** One urgency level's count, from {@link #countByUrgencyCreatedBetween}. */
    interface UrgencyCount {
        UrgencyLevel getUrgency();
        long getTotal();
    }

    /**
     * Requests raised in the range that received units from two or more different
     * blood banks — Business Rule 20's cooperative filling, read from the contribution
     * rows (the source of truth for who gave what), whatever the request's status now.
     */
    @Query("""
        select count(r) from BloodRequest r
        where r.createdAt >= :from and r.createdAt < :to
          and (select count(distinct c.bloodBank.id) from BloodRequestContribution c
               where c.bloodRequest = r) >= 2
        """)
    long countFilledByMoreThanOneBankCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    // "Units Issued" used to be summed here, from units_requested on requests this
    // bank had fulfilled. It now lives on BloodRequestContributionRepository, where
    // the units a bank actually issued are recorded one act at a time: with several
    // banks able to fill one request, a request's full size is no longer a bank's
    // own contribution, and the contribution's timestamp is the real issue date.
}
