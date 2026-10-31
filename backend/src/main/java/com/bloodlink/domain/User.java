package com.bloodlink.domain;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /**
     * Optional for DONOR, required otherwise (Business Rule 18, V13); null, never "", when absent.
     * Unique when present; any number of donors may have none.
     */
    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Role role;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Whether this account may sign in and act (V8, for the Admin module's activate/suspend).
     * Defaults to ACTIVE in Java and in the database, so every new account can sign in.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    /**
     * True while the password is one someone else set (Business Rule 27, V25); the account can then only change it.
     * Set for administrator-created and startup-created accounts, cleared by the account holder's own password change.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * When the password was last set or changed (Business Rule 32, V29); a token issued in an earlier second is refused.
     * Null for accounts untouched since V29, meaning no restriction.
     */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * The blood bank this account works at, set only for BLOOD_BANK_STAFF (V5); null for every other role.
     * Staff with no bank have access to nothing.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blood_bank_id")
    private BloodBank bloodBank;

    /**
     * The hospital this account works at, set only for HOSPITAL_STAFF (V7); null for every other role.
     * Hospital staff with no hospital have access to nothing.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    /** Stores a new password hash and when it was set, so tokens issued before it stop working (Business Rule 32). */
    public void replacePassword(String newPasswordHash, Instant changedAt) {
        this.passwordHash = newPasswordHash;
        this.passwordChangedAt = changedAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
