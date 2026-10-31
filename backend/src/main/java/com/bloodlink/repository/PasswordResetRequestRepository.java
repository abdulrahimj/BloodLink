package com.bloodlink.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.PasswordResetRequest;
import com.bloodlink.domain.enums.PasswordResetRequestStatus;

import jakarta.persistence.LockModeType;

public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, UUID> {

    /**
     * The account's waiting request, row-locked until the transaction ends, so a reset and a "Mark done" at the same
     * moment take turns (Business Rule 33). At most one exists (V30's partial unique index).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PasswordResetRequest r where r.user.id = :userId and r.status = "
        + "com.bloodlink.domain.enums.PasswordResetRequestStatus.WAITING")
    Optional<PasswordResetRequest> lockWaitingForUser(@Param("userId") UUID userId);

    /** One request, row-locked until the transaction ends, for "Mark done". */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PasswordResetRequest r where r.id = :id")
    Optional<PasswordResetRequest> lockById(@Param("id") UUID id);

    /**
     * One page of a status, with the account, its facility and the handler fetched for the response; the order
     * comes from the Pageable (newest first, built by the service).
     */
    @Query(
        value = "select r from PasswordResetRequest r join fetch r.user u left join fetch u.bloodBank "
            + "left join fetch u.hospital left join fetch r.handledBy where r.status = :status",
        countQuery = "select count(r) from PasswordResetRequest r where r.status = :status"
    )
    Page<PasswordResetRequest> findPageByStatus(@Param("status") PasswordResetRequestStatus status, Pageable pageable);

    long countByStatus(PasswordResetRequestStatus status);
}
