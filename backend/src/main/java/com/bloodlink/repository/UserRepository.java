package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {
}
