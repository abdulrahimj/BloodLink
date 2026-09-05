package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Donation;

public interface DonationRepository extends JpaRepository<Donation, UUID> {

    /** A donor's full history, most recent first — backs GET /api/donors/me/donations. */
    List<Donation> findByDonorIdOrderByDonationDateDesc(UUID donorId);
}
