package com.bloodlink.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.bloodlink.domain.enums.WriteOffReason;

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
 * Units of one batch discarded, why and by whom: the write-off ledger (V18), written only by BatchWriteOffService.
 * Append-only like ContributionAllocation; its STOCK_WRITTEN_OFF audit entry is written in the same transaction.
 */
@Entity
@Table(name = "batch_write_offs")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class BatchWriteOff {

    /** The longest note accepted; V18's CHECK constraint uses the same number. */
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private BloodInventoryBatch batch;

    @Column(name = "units", nullable = false, updatable = false)
    private int units;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 24, updatable = false)
    private WriteOffReason reason;

    /** Optional except for OTHER; never blank (blank is stored as null). */
    @Column(name = "note", updatable = false, columnDefinition = "text")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "written_off_by_user_id", nullable = false, updatable = false)
    private User writtenOffBy;

    @Column(name = "written_off_at", nullable = false, updatable = false)
    private Instant writtenOffAt;

    /**
     * The only way to make one; applies V18's note rules (blank to null, required for OTHER, max 500)
     * so a bad note gets a readable message before the database refuses it.
     */
    public static BatchWriteOff record(
        BloodInventoryBatch batch, int units, WriteOffReason reason, String note, User writtenOffBy
    ) {
        if (units <= 0) {
            throw new IllegalArgumentException("A write-off must be at least one unit.");
        }
        problemWithNote(reason, note).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });

        BatchWriteOff writeOff = new BatchWriteOff();
        writeOff.batch = batch;
        writeOff.units = units;
        writeOff.reason = reason;
        writeOff.note = cleanNote(note);
        writeOff.writtenOffBy = writtenOffBy;
        return writeOff;
    }

    /**
     * The same, stamped with an instant from the application's clock, so the entry, its audit entry
     * and the batch's last_updated share one instant.
     */
    public static BatchWriteOff record(
        BloodInventoryBatch batch, int units, WriteOffReason reason, String note, User writtenOffBy,
        Instant writtenOffAt
    ) {
        BatchWriteOff writeOff = record(batch, units, reason, note, writtenOffBy);
        writeOff.writtenOffAt = writtenOffAt;
        return writeOff;
    }

    /** A note as it is stored: trimmed, and blank becomes null (never ''). */
    public static String cleanNote(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }

    /**
     * What is wrong with a note for this reason, or empty if fine; returned rather than thrown so the endpoint
     * can attach it to its "note" field. One copy of the rule for both.
     */
    public static Optional<String> problemWithNote(WriteOffReason reason, String note) {
        String clean = cleanNote(note);
        if (reason.requiresNote() && clean == null) {
            return Optional.of("A note is required when the reason is " + reason + ".");
        }
        if (clean != null && clean.length() > NOTE_MAX_LENGTH) {
            return Optional.of("A note must be at most " + NOTE_MAX_LENGTH + " characters.");
        }
        return Optional.empty();
    }

    @PrePersist
    void onCreate() {
        if (writtenOffAt == null) {
            writtenOffAt = Instant.now();
        }
    }
}
