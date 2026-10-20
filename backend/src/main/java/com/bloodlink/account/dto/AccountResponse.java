package com.bloodlink.account.dto;

import java.util.UUID;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;

/**
 * The caller's own account as Profile Settings shows it, including the read-only phone, role and facility.
 * No password hash, and no status (always ACTIVE for anyone able to call this).
 */
public record AccountResponse(
    UUID id,
    String fullName,
    String email,
    String phone,
    Role role,
    /** Null for every role except BLOOD_BANK_STAFF. */
    UUID bloodBankId,
    String bloodBankName,
    /** Null for every role except HOSPITAL_STAFF. */
    UUID hospitalId,
    String hospitalName
) {

    /**
     * Flattens the entity for the wire; touches lazy bank and hospital associations, so call it
     * inside the transaction that loaded the User (open-in-view is off).
     */
    public static AccountResponse from(User user) {
        BloodBank bank = user.getBloodBank();
        Hospital hospital = user.getHospital();

        return new AccountResponse(
            user.getId(),
            user.getFullName(),
            user.getEmail(),
            user.getPhone(),
            user.getRole(),
            bank == null ? null : bank.getId(),
            bank == null ? null : bank.getName(),
            hospital == null ? null : hospital.getId(),
            hospital == null ? null : hospital.getName()
        );
    }
}
