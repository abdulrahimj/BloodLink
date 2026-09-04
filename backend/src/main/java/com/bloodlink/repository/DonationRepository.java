package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Donation;

public interface DonationRepository extends JpaRepository<Donation, UUID> {
}
