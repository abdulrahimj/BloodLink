package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.Notification;

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

    /**
     * Clears the badge in one statement, scoped by recipient only.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.read = true where n.recipient.id = :recipientId and n.read = false")
    int markAllReadForRecipient(@Param("recipientId") UUID recipientId);
}
