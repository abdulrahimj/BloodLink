package com.bloodlink.admin.dto;

import java.util.UUID;

import com.bloodlink.domain.enums.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/admin/users — an administrator creating a staff or
 * administrator account (Business Rule 14).
 *
 * <h2>No password field, deliberately</h2>
 * The administrator never chooses one. The server generates it, hashes it, and
 * returns the plaintext exactly once in the response. Accepting a password here
 * would mean an administrator could set a colleague's credential to something they
 * know and keep knowing it — see {@code TemporaryPasswordGenerator}.
 *
 * <h2>No status field either</h2>
 * Every account starts ACTIVE (V8, and the default on the User entity). An account
 * created suspended would be a staff member handed a password that does not work.
 *
 * <h2>Role is constrained by the service, not by an annotation</h2>
 * {@code role} is the full {@link Role} enum on the wire because that is what the
 * column stores, but DONOR is refused in {@code AdminUserService.createStaffAccount}:
 * donors self-register (Business Rule 8), which also creates the linked Donor row
 * this endpoint has no fields for. Bean Validation has no clean way to say "any of
 * this enum except one value", and a hand-rolled annotation for a single rule used
 * in a single place would hide it further from the rule it belongs to.
 */
public record CreateStaffAccountPayload(

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    String fullName,

    /**
     * Bounded to the column width (V1). The format rule — and the conversion to the
     * one stored format — is Business Rule 13's {@code PhoneNumber.normalize}, which
     * the service applies; a @Pattern here would be a second, drifting copy of it.
     */
    @NotBlank(message = "Phone number is required")
    @Size(max = 32)
    String phone,

    @NotBlank(message = "Email address is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 255)
    String email,

    @NotNull(message = "Role is required")
    Role role,

    /**
     * Required when role is BLOOD_BANK_STAFF, forbidden otherwise — checked in the
     * service, because which of these two is required depends on a different field
     * and no annotation on this one can see that.
     */
    UUID bloodBankId,

    /** Required when role is HOSPITAL_STAFF, forbidden otherwise. */
    UUID hospitalId
) {}
