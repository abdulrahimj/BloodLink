package com.bloodlink.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Phone is the login credential, so this is the lookup every authentication runs. */
    Optional<User> findByPhone(String phone);

    /** Pre-flight uniqueness checks at registration, so we can return a friendly
     *  409 instead of letting the database's unique constraints surface as a 500. */
    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);
}
