package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Hospital;

public interface HospitalRepository extends JpaRepository<Hospital, UUID> {
}
