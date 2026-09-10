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

    @Column(name = "email", nullable = false, unique = true)
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
     * Whether this account may currently sign in and act (V8, added for the Admin
     * module's activate/suspend use case — not part of the original CLAUDE.md schema
     * table, the same kind of confirmed addition as {@link #bloodBank} below).
     *
     * Defaults to ACTIVE in Java as well as at the database default, so every path
     * that creates a User — registration today, an admin-managed creation flow
     * later — starts a new account able to sign in without having to remember to
     * set this explicitly.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    /**
     * Which blood bank this account works at — only ever set for BLOOD_BANK_STAFF
     * (added in V5, after confirming with the project owner; not part of the
     * original CLAUDE.md schema table). Null for every other role, and null is also
     * valid for a not-yet-assigned staff account: there is no admin-managed account
     * creation flow yet, so InventoryService treats "staff with no bank" as having
     * access to nothing rather than failing to start up.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blood_bank_id")
    private BloodBank bloodBank;

    /**
     * Which hospital this account works at — only ever set for HOSPITAL_STAFF
     * (added in V7, the exact counterpart of {@link #bloodBank} above). Null for
     * every other role, and null is also valid for a not-yet-assigned staff
     * account, since there is still no admin-managed account creation flow:
     * RequestService treats "hospital staff with no hospital" as having access to
     * nothing rather than failing to start up.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
