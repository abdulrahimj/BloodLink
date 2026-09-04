package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.BloodBank;

public interface BloodBankRepository extends JpaRepository<BloodBank, UUID> {
}
