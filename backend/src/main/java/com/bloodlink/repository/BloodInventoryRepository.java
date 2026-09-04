package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.BloodInventory;

public interface BloodInventoryRepository extends JpaRepository<BloodInventory, UUID> {
}
