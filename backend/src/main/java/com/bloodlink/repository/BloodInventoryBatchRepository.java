package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.bloodlink.domain.BloodInventoryBatch;

/**
 * Batches of stock (V18). Nothing in the application uses this yet; the stock
 * queries the services need arrive with the cutover (Stage 2).
 *
 * A bare {@link Repository} rather than JpaRepository on purpose: batches are never
 * deleted (a used-up batch stays as history), so this repository inherits no
 * delete methods to be called by mistake.
 */
public interface BloodInventoryBatchRepository extends Repository<BloodInventoryBatch, UUID> {

    <S extends BloodInventoryBatch> S save(S batch);

    Optional<BloodInventoryBatch> findById(UUID id);

    /**
     * The stock identity check: every batch for which
     * {@code units_received = units_remaining + units allocated to hospitals + units written off}
     * does NOT hold. Should always be empty; anything returned is stock that has left
     * a shelf without a record of where it went, or a record of units that never left.
     *
     * Native SQL so the same query can be pasted into psql as it stands. Batches
     * converted from the old table at cutover (MIGRATED) start with
     * received = remaining and no history, so they satisfy it from that point on.
     */
    @Query(value = """
        select b.id
          from blood_inventory_batches b
         where b.units_received <> b.units_remaining
               + coalesce((select sum(a.units_allocated) from contribution_allocations a where a.batch_id = b.id), 0)
               + coalesce((select sum(w.units) from batch_write_offs w where w.batch_id = b.id), 0)
        """, nativeQuery = true)
    List<UUID> findIdsBreakingStockIdentity();
}
