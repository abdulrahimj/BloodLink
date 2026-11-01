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
     * "My Recent Requests": this hospital's own requests only, newest first, scoped in the query.
     */
    List<BloodRequest> findByHospitalIdOrderByCreatedAtDesc(UUID hospitalId);

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
     * A bank's incoming requests: every open one (Business Rule 20) plus every one it contributed to, newest first,
     * minus any it declined (Business Rule 11). So a cancelled request stays only as a contributor's history (Rule 35).
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
     * The Open Requests stat card: requests this bank could contribute to now (short of units, not declined by it),
     * the same scope as findActionableForBank minus closed history, so the card matches the rows.
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
