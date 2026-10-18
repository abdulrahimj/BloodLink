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
 * Writes and reads the audit log — and nothing else (Business Rule 17).
 *
 * Deliberately extends Spring Data's bare {@code Repository} marker, not
 * {@code JpaRepository} like every other repository here. JpaRepository would hand
 * every caller {@code delete}, {@code deleteAll}, {@code deleteAllInBatch} and
 * friends for free; this interface declares only what an append-only log needs,
 * so there is no method to call that removes an entry, and a later change that
 * wants one has to add it here, in plain sight.
 *
 * {@code save} can in principle update an entity that already has an id, but
 * AuditLog is {@code @Immutable} (Hibernate never issues the UPDATE) and V12's
 * trigger refuses one at the database regardless.
 */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    /** Records a new entry. Called only by AuditLogService.record. */
    AuditLog save(AuditLog entry);

    /**
     * One page of entries, newest first, with both people loaded — the Audit Log
     * page. Fetch-joined because the page shows both names on every row, and
     * open-in-view is off, so the lazy associations would otherwise fail once
     * mapped outside the transaction (or cost one query per row inside it). The
     * fetch joins are all to-one (actor, target, targetBloodBank, targetHospital),
     * so pagination's LIMIT/OFFSET is still applied to whole rows, not fragments
     * of a to-many collection.
     *
     * The four subjects are LEFT joins because each entry has exactly one of them
     * (V14, V18): an inner join on the account would silently drop every facility
     * creation and write-off from the page, and so on for each of the others. A
     * write-off's batch is fetched with its bank, since the page names both; still
     * to-one all the way down.
     *
     * {@code order by a.createdAt desc, a.id desc} — the tie-breaker. Two entries
     * can share a timestamp (they are often written back-to-back in the same
     * transaction); ordering by id as well means their relative order can never
     * flip between one page request and the next. The caller's {@link Pageable}
     * is expected to carry no {@link org.springframework.data.domain.Sort} of its
     * own — this fixed order is the only one the page ever shows — so only the
     * paging (page number, size) part of it is used.
     *
     * {@code countQuery} is given explicitly rather than left for Spring Data to
     * derive from the query above: a derived count query would have to strip the
     * {@code fetch} keyword and the {@code order by} itself, which is one more
     * thing to trust than simply saying what the total is.
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
