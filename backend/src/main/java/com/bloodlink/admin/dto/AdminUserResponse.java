package com.bloodlink.admin.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;

/**
 * One user account as the Admin Console's User Accounts table sees it.
 * Carries both id and name of the linked facility, sparing the frontend a lookup.
 */
public record AdminUserResponse(
    UUID id,
    String fullName,
    String email,
    String phone,
    Role role,
    AccountStatus status,
    /** Null for every role except BLOOD_BANK_STAFF, and even then only once an
     *  administrator has assigned one. */
    UUID bloodBankId,
    String bloodBankName,
    /** Null for every role except HOSPITAL_STAFF, the same way. */
    UUID hospitalId,
    String hospitalName,
    Instant createdAt
) {

    /**
     * Flattens the entity for the wire; touches the lazy bank and hospital associations,
     * so call it inside a transaction (open-in-view is off), ideally after a fetch join.
     */
    public static AdminUserResponse from(User user) {
        BloodBank bank = user.getBloodBank();
        Hospital hospital = user.getHospital();

        return new AdminUserResponse(
            user.getId(),
            user.getFullName(),
            user.getEmail(),
            user.getPhone(),
            user.getRole(),
            user.getStatus(),
            bank == null ? null : bank.getId(),
            bank == null ? null : bank.getName(),
            hospital == null ? null : hospital.getId(),
            hospital == null ? null : hospital.getName(),
            user.getCreatedAt()
        );
    }
}
