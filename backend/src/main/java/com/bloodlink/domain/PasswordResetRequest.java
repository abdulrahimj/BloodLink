package com.bloodlink.domain;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.enums.PasswordResetRequestStatus;
import com.bloodlink.domain.enums.PasswordResetResolution;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Someone's request, from the Sign in page, for an administrator to call them about a forgotten password
 * (Business Rule 33, V30). It never resets anything itself; an administrator does that (Business Rule 31).
 */
@Entity
@Table(name = "password_reset_requests")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The latest time they asked; moved forward when they ask again an hour or more later. */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PasswordResetRequestStatus status = PasswordResetRequestStatus.WAITING;

    /** How it was handled; null while waiting (V30 CHECK). */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 16)
    private PasswordResetResolution resolution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private User handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    /** The administrator's note when closing without a reset; at most 200 characters, never blank. */
    @Column(name = "note", length = 200)
    private String note;

    /** A new waiting request for this account, asked at this moment. */
    public static PasswordResetRequest waiting(User user, Instant requestedAt) {
        PasswordResetRequest request = new PasswordResetRequest();
        request.user = user;
        request.requestedAt = requestedAt;
        return request;
    }

    public boolean isWaiting() {
        return status == PasswordResetRequestStatus.WAITING;
    }

    /** Marks it done by this administrator, at this moment, one way or the other (the note only for CLOSED). */
    public void handle(PasswordResetResolution how, User administrator, Instant at, String withNote) {
        status = PasswordResetRequestStatus.DONE;
        resolution = how;
        handledBy = administrator;
        handledAt = at;
        note = how == PasswordResetResolution.CLOSED ? withNote : null;
    }
}
