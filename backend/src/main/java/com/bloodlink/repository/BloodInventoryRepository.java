package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.enums.BloodType;

public interface BloodInventoryRepository extends JpaRepository<BloodInventory, UUID> {

    /** Every tracked blood group's stock row for one bank — never more than 8. */
    List<BloodInventory> findByBloodBankId(UUID bloodBankId);

    /** The one row for (bank, group) — at most one, per the uq_blood_inventory_bank_type
     *  constraint — looked up before an "Add New Batch" upsert. */
    Optional<BloodInventory> findByBloodBankIdAndBloodType(UUID bloodBankId, BloodType bloodType);
}
