package com.bloodlink.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.bloodlink.domain.BatchWriteOff;

/**
 * The write-off ledger (V18). Append-only: a bare {@link Repository} with no way to
 * change or remove an entry. Nothing in the application uses it yet.
 */
public interface BatchWriteOffRepository extends Repository<BatchWriteOff, UUID> {

    <S extends BatchWriteOff> S save(S writeOff);

    Optional<BatchWriteOff> findById(UUID id);
}
