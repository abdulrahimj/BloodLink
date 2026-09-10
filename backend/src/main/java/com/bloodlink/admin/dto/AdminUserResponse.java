package com.bloodlink.admin.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;

/**
 * One user account as the Admin Console's "User Accounts" table sees it.
 *
 * Both the id and the name are carried for the linked facility, the same pattern
 * BloodRequestResponse already uses for hospital and fulfilling-bank: the table
 * displays a name, but a future facility-scoped action would need the id, and
 * returning both spares the frontend a lookup against a separate reference list.
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
     * Flattens the entity for the wire. Safe to call inside a transaction only —
     * bloodBank and hospital are both lazy associations, and this touches them
     * (open-in-view is false, so a detached entity would fail here). Callers are
     * expected to have loaded them already, e.g. via a fetch join.
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
