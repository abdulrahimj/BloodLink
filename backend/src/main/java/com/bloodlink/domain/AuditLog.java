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
 *
 * <h2>One subject per entry</h2>
 * An account action's subject is {@link #target}; a facility creation's (Business
 * Rule 19, V14) is {@link #targetBloodBank} or {@link #targetHospital}; a stock
 * write-off's (Business Rule 24, V18) is {@link #targetBatch}. Exactly one of the
 * four is set, matching the action — the factory methods below build only those
 * shapes, and V18's CHECK constraint refuses any other at the database.
 *
 * The actor is an administrator for every action except STOCK_WRITTEN_OFF, whose
 * actor is a member of the batch's own bank's staff (an administrator cannot write off).
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

    /** The account the action was done to — set for the four ACCOUNT_ actions,
     *  null for a facility creation (V14). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id", updatable = false)
    private User target;

    /** The blood bank created — set only for BLOOD_BANK_CREATED (Business Rule 19). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_blood_bank_id", updatable = false)
    private BloodBank targetBloodBank;

    /** The hospital created — set only for HOSPITAL_CREATED (Business Rule 19). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_hospital_id", updatable = false)
    private Hospital targetHospital;

    /** The batch units were written off from — set only for STOCK_WRITTEN_OFF (V18). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_batch_id", updatable = false)
    private BloodInventoryBatch targetBatch;

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
        requireAccountAction(action);
        AuditLog entry = new AuditLog();
        entry.actor = actor;
        entry.action = action;
        entry.target = target;
        entry.details = details;
        return entry;
    }

    /** A new blood bank, as a BLOOD_BANK_CREATED entry (Business Rule 19). */
    public static AuditLog recordBloodBankCreated(User actor, BloodBank bank, String details) {
        AuditLog entry = new AuditLog();
        entry.actor = actor;
        entry.action = AuditAction.BLOOD_BANK_CREATED;
        entry.targetBloodBank = bank;
        entry.details = details;
        return entry;
    }

    /** A new hospital, as a HOSPITAL_CREATED entry (Business Rule 19). */
    public static AuditLog recordHospitalCreated(User actor, Hospital hospital, String details) {
        AuditLog entry = new AuditLog();
        entry.actor = actor;
        entry.action = AuditAction.HOSPITAL_CREATED;
        entry.targetHospital = hospital;
        entry.details = details;
        return entry;
    }

    /**
     * Units written off one batch, as a STOCK_WRITTEN_OFF entry (Business Rule 24).
     * Stamped with {@code createdAt} from the application's clock, the same instant
     * as the write-off it records, rather than left to {@link #onCreate}.
     */
    public static AuditLog recordStockWrittenOff(
        User actor, BloodInventoryBatch batch, String details, Instant createdAt
    ) {
        AuditLog entry = new AuditLog();
        entry.actor = actor;
        entry.action = AuditAction.STOCK_WRITTEN_OFF;
        entry.targetBatch = batch;
        entry.details = details;
        entry.createdAt = createdAt;
        return entry;
    }

    /**
     * Refuses a facility action passed through the account factory. V14's
     * audit_log_target_matches_action constraint would refuse the row anyway; this
     * fails at the line that made the mistake, instead of at a later flush.
     */
    private static void requireAccountAction(AuditAction action) {
        if (!action.isAccountAction()) {
            throw new IllegalStateException(action + " does not take a user account as its subject.");
        }
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
