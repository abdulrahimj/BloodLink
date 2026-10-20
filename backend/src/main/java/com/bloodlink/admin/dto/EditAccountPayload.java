package com.bloodlink.admin.dto;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/admin/users/{userId}/details (Business Rule 17): always the full desired phone and facility.
 * role is refused with a 400 (out of scope), as is anything else undeclared, via unrecognised.
 */
public record EditAccountPayload(

    /** Normalised and duplicate-checked by Business Rule 13's PhoneNumber.normalize. */
    @NotBlank(message = "Phone number is required")
    @Size(max = 32)
    String phone,

    /** Required when the account is BLOOD_BANK_STAFF, forbidden otherwise. */
    UUID bloodBankId,

    /** Required when the account is HOSPITAL_STAFF, forbidden otherwise. */
    UUID hospitalId,

    /** Every other property in the body, captured so it can be refused out loud. */
    @JsonAnySetter
    Map<String, Object> unrecognised
) {}
