package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BatchWriteOff;

/**
 * The write-off ledger (V18). Append-only: a bare {@link Repository} with no way to
 * change or remove an entry. Nothing writes to it until the write-off action
 * (Stage 3); the batch history already reads it, so that page needs no change then.
 */
public interface BatchWriteOffRepository extends Repository<BatchWriteOff, UUID> {

    <S extends BatchWriteOff> S save(S writeOff);

    Optional<BatchWriteOff> findById(UUID id);

    /** One batch's write-offs, oldest first, with who made them loaded. */
    @Query("""
        select w from BatchWriteOff w
          join fetch w.writtenOffBy
         where w.batch.id = :batchId
         order by w.writtenOffAt asc, w.id asc
        """)
    List<BatchWriteOff> findForBatch(@Param("batchId") UUID batchId);
}
