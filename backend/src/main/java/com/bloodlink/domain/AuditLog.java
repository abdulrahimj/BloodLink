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
 * One audit log entry (Business Rule 17, V12): append-only via no setters, @Immutable, no update path, V12 triggers.
 * Exactly one subject per entry (account, facility or batch), matching the action and enforced by a CHECK.
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

    /** Who acted: an administrator, or bank staff for STOCK_WRITTEN_OFF and DONOR_BLOOD_GROUP_UPDATED. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false, updatable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32, updatable = false)
    private AuditAction action;

    /** The account the action was done to — set for the four ACCOUNT_ actions, DONOR_BLOOD_GROUP_UPDATED and
     *  ADMIN_PASSWORD_RESET and PASSWORD_REQUEST_CLOSED, null for a facility creation (V14). */
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
     * A STOCK_WRITTEN_OFF entry (Business Rule 24), stamped with the same clock instant as the write-off.
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
     * Refuses a facility action passed through the account factory,
     * failing here rather than at a later flush against V14's constraint.
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
