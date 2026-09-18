package com.bloodlink.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.bloodlink.domain.enums.AuditAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One entry in the administrators' audit log — Business Rule 17, table V12.
 *
 * <h2>Append-only, from four directions</h2>
 * <ul>
 *   <li><b>No setters.</b> An entry is built once, by {@link #record}, and has no
 *       way to be altered afterwards in Java.</li>
 *   <li><b>{@code @Immutable}.</b> Hibernate never issues an UPDATE for this entity,
 *       even if something did manage to change a loaded instance.</li>
 *   <li><b>No update or delete path.</b> AuditLogRepository declares only
 *       {@code save} and reads, and no endpoint does anything but read.</li>
 *   <li><b>Database triggers (V12).</b> UPDATE, DELETE and TRUNCATE on the table
 *       fail, whatever issued them.</li>
 * </ul>
 * Any one would do in the ordinary course; together they mean a mistake in one
 * layer is still caught by the next.
 *
 * Both people are held as associations rather than copied names, so the log page
 * shows who they are now; what the action changed is captured as text in
 * {@link #details} at the moment it happened, so that part never shifts.
 */
@Entity
@Table(name = "audit_log")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The administrator who acted. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false, updatable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32, updatable = false)
    private AuditAction action;

    /** The account the action was done to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_user_id", nullable = false, updatable = false)
    private User target;

    /** What changed, in words, as of the moment it changed. Never a password. */
    @Column(name = "details", nullable = false, updatable = false, columnDefinition = "text")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The only way to make an entry. Everything an entry will ever say is fixed
     * here; there is no later step that fills anything in.
     */
    public static AuditLog record(User actor, AuditAction action, User target, String details) {
        AuditLog entry = new AuditLog();
        entry.actor = actor;
        entry.action = action;
        entry.target = target;
        entry.details = details;
        return entry;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
