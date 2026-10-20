package com.bloodlink.auth.dto;

import java.time.LocalDate;

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/auth/register, a donor signing up; no role field, so nobody can mint an admin.
 * bloodType is the clinical label ("A+"), nullable for "I don't know yet".
 */
public record RegisterRequest(

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    String fullName,

    @NotBlank(message = "Phone number is required")
    @Size(max = 32)
    String phone,

    /**
     * Optional (Business Rule 18): blank means no email; AuthService checks the format (EmailAddress.normalizeOptional)
     * because @Email is too loose. Only the length is bounded here.
     */
    @Size(max = 255, message = "Email address must be 255 characters or fewer")
    String email,

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    LocalDate dateOfBirth,

    @NotNull(message = "Gender is required")
    Gender gender,

    /**
     * Nullable for "I don't know yet"; when given, must look like a real label so a garbage value
     * gets a field-level 400 on bloodType (the service still resolves it with BloodType.fromLabel).
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
