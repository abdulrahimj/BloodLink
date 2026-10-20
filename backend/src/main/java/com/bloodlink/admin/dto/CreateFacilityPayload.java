package com.bloodlink.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating a blood bank or hospital (Business Rule 19); sizes match V1's columns.
 * contactPhone is only required, never normalised: it is display-only and may be a landline or carry an extension.
 */
public record CreateFacilityPayload(

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be 255 characters or fewer")
    String name,

    @NotBlank(message = "Location is required")
    @Size(max = 255, message = "Location must be 255 characters or fewer")
    String location,

    @NotBlank(message = "Contact phone is required")
    @Size(max = 32, message = "Contact phone must be 32 characters or fewer")
    String contactPhone
) {}
