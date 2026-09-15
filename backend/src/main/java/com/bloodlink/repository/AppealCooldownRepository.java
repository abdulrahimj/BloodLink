package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.enums.BloodType;

/**
 * The per-(bank, group) appeal cooldown rows behind Business Rule 12.
 *
 * Two questions only, one per caller: the inventory read path needs every
 * cooldown for one bank at once to render the table, and the send path needs the
 * single row for the group being appealed for.
 */
public interface AppealCooldownRepository extends JpaRepository<AppealCooldown, UUID> {

    /**
     * Every cooldown this bank holds — at most eight, one per blood group.
     *
     * One query for the whole inventory table rather than a lookup per row: the
     * table always renders all eight groups, so a per-row query would be eight
     * round trips to answer what one returns.
     */
    List<AppealCooldown> findByBloodBankId(UUID bloodBankId);

    /** The one row for (bank, group), read before a send and updated after it. */
    Optional<AppealCooldown> findByBloodBankIdAndBloodType(UUID bloodBankId, BloodType bloodType);
}
