package com.bloodlink.admin.dto;

import java.util.UUID;

import com.bloodlink.domain.enums.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/admin/users (Business Rule 14): no password (the server generates it), no status (ACTIVE).
 * DONOR is refused in AdminUserService.createStaffAccount, since donors self-register with a Donor row.
 */
public record CreateStaffAccountPayload(

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    String fullName,

    /**
     * Bounded to the column width; the format rule is Business Rule 13's PhoneNumber.normalize,
     * applied by the service, so a @Pattern here would only be a drifting copy.
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
     * Required when role is BLOOD_BANK_STAFF, forbidden otherwise;
     * checked in the service because it depends on another field.
     */
    UUID bloodBankId,

    /** Required when role is HOSPITAL_STAFF, forbidden otherwise. */
    UUID hospitalId
) {}
