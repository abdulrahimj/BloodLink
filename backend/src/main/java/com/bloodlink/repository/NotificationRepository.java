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
     * The notification bell's dropdown — this user's own, newest first.
     *
     * Capped at 50 rather than unbounded: a busy blood bank accumulates alerts
     * indefinitely and the panel only ever shows the recent ones, so fetching the
     * whole history on every page load would grow slower forever with nothing
     * gained. There is no "older notifications" screen yet; when there is, it wants
     * a paged query rather than this one widened.
     */
    List<Notification> findTop50ByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    /** The unread badge on the bell. Counted rather than derived from the list
     *  above, so an unread notification older than the newest 50 still counts. */
    long countByRecipientIdAndReadFalse(UUID recipientId);

    /**
     * One notification, but only if it belongs to this user.
     *
     * Recipient is part of the lookup rather than checked afterwards so that
     * "mark as read" cannot be pointed at someone else's notification id: a
     * mismatch returns empty and becomes a 404, revealing nothing about whether
     * that id exists at all.
     */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    /**
     * Clears the badge in one statement — "Mark all read" would otherwise load
     * every unread row into memory only to flip one boolean on each.
     *
     * Scoped by recipient, never by a shared attribute, per CLAUDE.md's rule about
     * writes that select on something more than one person's rows can match.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.read = true where n.recipient.id = :recipientId and n.read = false")
    int markAllReadForRecipient(@Param("recipientId") UUID recipientId);
}
