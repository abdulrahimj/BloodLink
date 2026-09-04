package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.BloodRequest;

public interface BloodRequestRepository extends JpaRepository<BloodRequest, UUID> {
}
