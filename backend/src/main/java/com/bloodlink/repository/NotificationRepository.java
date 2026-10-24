package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.Notification;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.NotificationType;
import jakarta.persistence.LockModeType;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * The bell's dropdown: this user's own notifications, newest first,
     * capped at 50 since the panel only shows recent ones.
     */
    List<Notification> findTop50ByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    /** The unread badge on the bell. Counted rather than derived from the list
     *  above, so an unread notification older than the newest 50 still counts. */
    long countByRecipientIdAndReadFalse(UUID recipientId);

    /**
     * One notification only if it belongs to this user, so marking another user's id read is a 404.
     */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    /** Latest accepted donor appeal per requested group, scoped to the originating bank. */
    @Query("""
        select n.bloodType as bloodType, max(n.createdAt) as latestAppealAt
        from Notification n
        where n.sourceBloodBank.id = :bloodBankId
          and n.type = com.bloodlink.domain.enums.NotificationType.DONATION_APPEAL
          and n.accepted = true
        group by n.bloodType
        """)
    List<AcceptedAppealGroup> findAcceptedAppealGroups(@Param("bloodBankId") UUID bloodBankId);

    interface AcceptedAppealGroup {
        BloodType getBloodType();
        Instant getLatestAppealAt();
    }

    /** Serializes acceptance attempts for this recipient's notification. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id and n.recipient.id = :recipientId")
    Optional<Notification> findByIdAndRecipientIdForUpdate(
        @Param("id") UUID id,
        @Param("recipientId") UUID recipientId
    );

    /** Whether a notice of this type for this bank was already written in [from, to): the daily expiry job's
     *  duplicate guard (Business Rule 26). */
    @Query("""
        select case when count(n) > 0 then true else false end from Notification n
         where n.type = :type and n.sourceBloodBank.id = :bloodBankId
           and n.createdAt >= :from and n.createdAt < :to
        """)
    boolean existsForBankBetween(
        @Param("type") NotificationType type,
        @Param("bloodBankId") UUID bloodBankId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    /**
     * Clears the badge in one statement, scoped by recipient only.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.read = true where n.recipient.id = :recipientId and n.read = false")
    int markAllReadForRecipient(@Param("recipientId") UUID recipientId);
}
