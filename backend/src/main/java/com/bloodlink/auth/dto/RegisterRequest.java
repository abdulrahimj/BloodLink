package com.bloodlink.auth.dto;

import java.time.LocalDate;

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/auth/register — a voluntary donor signing themselves up.
 *
 * Fields correspond one-to-one with the inputs on Register.jsx. Only donors
 * self-register; staff and administrator accounts are created by an administrator,
 * so no role field is accepted here (accepting one would let anyone mint an admin).
 *
 * bloodType is a String rather than the BloodType enum because the wire format is the
 * clinical label ("A+", "O-"), matching what the database stores; the service converts
 * it. It is nullable for the "I don't know yet" option — see V2__auth_constraints.sql.
 */
public record RegisterRequest(

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    String fullName,

    @NotBlank(message = "Phone number is required")
    @Size(max = 32)
    String phone,

    @NotBlank(message = "Email address is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 255)
    String email,

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    LocalDate dateOfBirth,

    @NotNull(message = "Gender is required")
    Gender gender,

    /**
     * Nullable by design: "I don't know yet". When given, bounded to the shape a
     * real label can take ("A+", "AB-") — the service still resolves the exact
     * value via BloodType.fromLabel (whose failure is also a 400), but catching an
     * obviously-wrong value here means a garbage string gets a field-level message
     * pointing at "bloodType" specifically, rather than a generic one.
     */
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\", or leave blank")
    String bloodType,

    @NotBlank(message = "Area / community is required")
    @Size(max = 255)
    String address,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be at least 8 characters")
    String password
) {}
