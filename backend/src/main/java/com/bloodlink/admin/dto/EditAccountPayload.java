package com.bloodlink.admin.dto;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/admin/users/{userId}/details — an administrator changing another
 * account's phone and facility (Business Rule 17).
 *
 * <h2>The whole desired state, every time</h2>
 * Phone is always sent, and so is whichever facility the account's role requires —
 * the same required/forbidden-per-role rule account creation applies (Business Rule
 * 14), judged against the role the account already has. The Edit form always
 * submits both, pre-filled with what is there now, so "unchanged" is expressed by
 * sending the current value back; an edit that changes nothing records nothing.
 *
 * <h2>Not role</h2>
 * Changing a role is deliberately out of scope: a DONOR account has a linked Donor
 * row (blood group, date of birth, donation history) that a role change would
 * orphan or would need creating, and that needs its own decision. A {@code role} in
 * the request is refused with a 400 saying so — not ignored — as is anything else
 * not declared here (name, email, status), via {@link #unrecognised}, the same
 * capture-and-refuse approach as the self-service account endpoints.
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
