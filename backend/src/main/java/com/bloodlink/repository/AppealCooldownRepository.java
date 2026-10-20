package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.enums.BloodType;

/**
 * The per-(bank, group) cooldown rows behind Business Rule 12: all of one bank's for the inventory table,
 * and one row for the send path.
 */
public interface AppealCooldownRepository extends JpaRepository<AppealCooldown, UUID> {

    /**
     * Every cooldown this bank holds (at most eight), in one query for the whole inventory table.
     */
    List<AppealCooldown> findByBloodBankId(UUID bloodBankId);

    /** The one row for (bank, group), read before a send and updated after it. */
    Optional<AppealCooldown> findByBloodBankIdAndBloodType(UUID bloodBankId, BloodType bloodType);
}
