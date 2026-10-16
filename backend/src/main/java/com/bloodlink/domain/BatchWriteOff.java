package com.bloodlink.domain;

import java.time.Instant;
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
 * Units of one batch discarded, why, and by whom — the write-off ledger of the
 * batch-stock redesign (table V18). Nothing in the application writes these yet.
 *
 * Append-only for the same reasons and by the same four means as
 * {@link ContributionAllocation}. The matching STOCK_WRITTEN_OFF audit-log entry is
 * written alongside it by the write-off service (a later stage), in the same
 * transaction.
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
     * The only way to make one. Applies the same note rules as V18's CHECK
     * constraints (blank becomes null, required for OTHER, at most 500 characters),
     * so a bad note is refused here, with a readable message, before the database
     * would refuse it anyway.
     */
    public static BatchWriteOff record(
        BloodInventoryBatch batch, int units, WriteOffReason reason, String note, User writtenOffBy
    ) {
        if (units <= 0) {
            throw new IllegalArgumentException("A write-off must be at least one unit.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (reason.requiresNote() && cleanNote == null) {
            throw new IllegalArgumentException("A note is required when the reason is " + reason + ".");
        }
        if (cleanNote != null && cleanNote.length() > NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("A note must be at most " + NOTE_MAX_LENGTH + " characters.");
        }

        BatchWriteOff writeOff = new BatchWriteOff();
        writeOff.batch = batch;
        writeOff.units = units;
        writeOff.reason = reason;
        writeOff.note = cleanNote;
        writeOff.writtenOffBy = writtenOffBy;
        return writeOff;
    }

    @PrePersist
    void onCreate() {
        if (writtenOffAt == null) {
            writtenOffAt = Instant.now();
        }
    }
}
