package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Donor;

public interface DonorRepository extends JpaRepository<Donor, UUID> {
}
