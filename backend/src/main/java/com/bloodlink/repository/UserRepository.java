package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Phone is the login credential, so this is the lookup every authentication runs. */
    Optional<User> findByPhone(String phone);

    /** Pre-flight uniqueness checks at registration, so we can return a friendly
     *  409 instead of letting the database's unique constraints surface as a 500. */
    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    /**
     * The staff accounts attached to one facility — who a notification about that
     * facility is addressed to.
     *
     * Role is part of the query rather than assumed from the column, because
     * users.blood_bank_id and users.hospital_id are both nullable columns on a
     * shared table: matching on the id alone would be correct today only because
     * no other role is ever given one, which is a rule enforced nowhere in the
     * database. Naming the role makes the query say what it means.
     */
    List<User> findByRoleAndBloodBankId(Role role, UUID bloodBankId);

    List<User> findByRoleAndHospitalId(Role role, UUID hospitalId);

    /**
     * Every account holding one role, regardless of facility.
     *
     * Used only as the fallback for a new-request alert that no bank currently holds
     * the stock to cover: with no capable bank to single out, the alert goes to all
     * bank staff rather than to nobody. Not a general-purpose listing — anything
     * user-facing should be scoped to a facility.
     */
    List<User> findByRole(Role role);
}
