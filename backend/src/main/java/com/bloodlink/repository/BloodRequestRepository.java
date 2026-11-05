package com.bloodlink.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.UrgencyLevel;

import jakarta.persistence.LockModeType;

public interface BloodRequestRepository
    extends JpaRepository<BloodRequest, UUID>, JpaSpecificationExecutor<BloodRequest> {

    /**
     * The Hospital dashboard's list: this hospital's OPEN requests only, newest first (finished ones are on Request
     * history, Business Rule 36). id breaks ties, so rows sharing a created_at never swap places between re-reads.
     */
    @Query("""
        select r from BloodRequest r
        where r.hospital.id = :hospitalId
          and r.status in (com.bloodlink.domain.enums.RequestStatus.PENDING,
                           com.bloodlink.domain.enums.RequestStatus.PARTIALLY_FULFILLED)
        order by r.createdAt desc, r.id desc
        """)
    List<BloodRequest> findOpenForHospital(@Param("hospitalId") UUID hospitalId);

    // ---------- the Hospital dashboard's stat cards, counted in the database ----------

    /** Requests this hospital raised in [from, to): "Total Requests, this month". */
    long countByHospitalIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(UUID hospitalId, Instant from, Instant to);

    /** This hospital's requests in the given statuses: "Awaiting Units" when passed the two open ones. */
    long countByHospitalIdAndStatusIn(UUID hospitalId, Collection<RequestStatus> statuses);

    /** The same, of one urgency: "Emergency Cases" when passed EMERGENCY and the open statuses. */
    long countByHospitalIdAndUrgencyLevelAndStatusIn(
        UUID hospitalId, UrgencyLevel urgencyLevel, Collection<RequestStatus> statuses);

    /**
     * Every request's status counted, citywide, for the Request Outcomes chart, in one grouped query.
     */
    @Query("select r.status as status, count(r) as total from BloodRequest r group by r.status")
    List<StatusCount> countGroupedByStatus();

    /** One status's count, from {@link #countGroupedByStatus}. */
    interface StatusCount {
        RequestStatus getStatus();
        long getTotal();
    }

    /**
     * A bank's incoming requests: every OPEN one it has not declined (Business Rules 11, 20), newest first. Finished
     * ones, its own contributions included, are on Request history (Business Rule 36). id breaks ties in the order.
     */
    @Query("""
        select r from BloodRequest r
        where r.status in (com.bloodlink.domain.enums.RequestStatus.PENDING,
                           com.bloodlink.domain.enums.RequestStatus.PARTIALLY_FULFILLED)
          and not exists (
              select 1 from BloodRequestDecline d
              where d.bloodRequest.id = r.id and d.bloodBank.id = :bloodBankId
          )
        order by r.createdAt desc, r.id desc
        """)
    List<BloodRequest> findActionableForBank(@Param("bloodBankId") UUID bloodBankId);

    /** Every request, newest first (id breaking ties) — the ADMIN's citywide view. */
    List<BloodRequest> findAllByOrderByCreatedAtDescIdDesc();

    /**
     * The Open Requests stat card: requests this bank could contribute to now (short of units, not declined by it),
     * the same scope as findActionableForBank, so the card matches the rows.
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
     * Loads one request under a row write lock for contribute, decline and cancel: the whole concurrency guarantee of
     * Business Rules 20 and 35, so simultaneous actions take turns and each sees what the one before it did.
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
     * Requests raised in the range that received units from two or more banks,
     * read from the contribution rows (Business Rule 20).
     */
    @Query("""
        select count(r) from BloodRequest r
        where r.createdAt >= :from and r.createdAt < :to
          and (select count(distinct c.bloodBank.id) from BloodRequestContribution c
               where c.bloodRequest = r) >= 2
        """)
    long countFilledByMoreThanOneBankCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    // "Units Issued" now lives on BloodRequestContributionRepository, summed from the units each bank actually issued,
    // since a request's full size is no longer one bank's contribution.
}
