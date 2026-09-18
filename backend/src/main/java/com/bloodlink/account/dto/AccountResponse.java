package com.bloodlink.account.dto;

import java.util.UUID;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;

/**
 * The signed-in user's own account, as the Profile Settings screen shows it.
 *
 * Carries the fields the user cannot change (phone, role, facility) as well as the
 * two they can (name, email), because the screen shows both kinds — the first as
 * read-only facts with a note on who can change them, so nobody goes looking for an
 * edit box that is deliberately not there.
 *
 * No password hash, and no account status: the first must never leave the server,
 * and the second is always ACTIVE for anyone able to call this endpoint at all (a
 * suspended account's token is refused before it gets here).
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
     * Flattens the entity for the wire. Touches the lazy bloodBank and hospital
     * associations, so it must be called inside the transaction that loaded the
     * User (open-in-view is off) — AccountService's methods all are.
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
