package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

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
     * Every entry, newest first, with both people loaded — the Audit Log page.
     * Fetch-joined because the page shows both names on every row, and open-in-view
     * is off, so the lazy associations would otherwise fail once mapped outside the
     * transaction (or cost one query per row inside it).
     *
     * The three subjects are LEFT joins because each entry has exactly one of them
     * (V14): an inner join on the account would silently drop every facility
     * creation from the page, and an inner join on a facility every account action.
     */
    @Query("select a from AuditLog a join fetch a.actor left join fetch a.target"
        + " left join fetch a.targetBloodBank left join fetch a.targetHospital"
        + " order by a.createdAt desc, a.id desc")
    List<AuditLog> findAllNewestFirst();

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

    long count();
}
