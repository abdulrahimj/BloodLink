package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.AuditLog;

/**
 * Writes and reads the audit log only (Business Rule 17): a bare Repository, not JpaRepository,
 * so no delete method exists. AuditLog is @Immutable and V12's trigger refuses updates anyway.
 */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    /** Records a new entry. Called only by AuditLogService.record. */
    AuditLog save(AuditLog entry);

    /**
     * One page of entries, newest first, with actor and all four possible subjects LEFT fetch-joined (all to-one,
     * so paging stays correct). Ordered by createdAt then id so pages are stable; count query given explicitly.
     */
    @Query(
        value = "select a from AuditLog a join fetch a.actor left join fetch a.target"
            + " left join fetch a.targetBloodBank left join fetch a.targetHospital"
            + " left join fetch a.targetBatch tb left join fetch tb.bloodBank"
            + " order by a.createdAt desc, a.id desc",
        countQuery = "select count(a) from AuditLog a"
    )
    Page<AuditLog> findAllNewestFirst(Pageable pageable);

    /** Entries about one account, oldest first — used by tests to check exactly
     *  what an action recorded. */
    @Query("select a from AuditLog a join fetch a.actor join fetch a.target where a.target.id = :targetId order by a.createdAt asc")
    List<AuditLog> findByTargetIdOldestFirst(@Param("targetId") UUID targetId);

    /** Creation entries for one blood bank — used by tests (Business Rule 19). */
    @Query("select a from AuditLog a join fetch a.actor join fetch a.targetBloodBank where a.targetBloodBank.id = :bankId")
    List<AuditLog> findByTargetBloodBankId(@Param("bankId") UUID bankId);

    /** Creation entries for one hospital — used by tests (Business Rule 19). */
    @Query("select a from AuditLog a join fetch a.actor join fetch a.targetHospital where a.targetHospital.id = :hospitalId")
    List<AuditLog> findByTargetHospitalId(@Param("hospitalId") UUID hospitalId);

    /** Write-off entries for one batch, oldest first — used by tests (Business Rule 24). */
    @Query("select a from AuditLog a join fetch a.actor join fetch a.targetBatch"
        + " where a.targetBatch.id = :batchId order by a.createdAt asc, a.id asc")
    List<AuditLog> findByTargetBatchIdOldestFirst(@Param("batchId") UUID batchId);

    long count();
}
