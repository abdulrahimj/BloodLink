package com.bloodlink.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/admin/blood-banks and POST /api/admin/hospitals — an
 * administrator adding a facility (Business Rule 19). One record for both, because
 * the two tables have exactly the same three columns (V1) and the same rules.
 *
 * Sizes match the column widths in V1, so an over-long value is a 400 naming the
 * field rather than a database error.
 *
 * <h2>contactPhone is not normalised</h2>
 * Deliberately only required, not checked against Business Rule 13. A facility's
 * number is display-only — nothing compares it and nobody signs in with it — and a
 * front desk line may legitimately be a landline or carry an extension, which
 * {@code PhoneNumber.normalize} would refuse. Rule 13 already says so: it applies
 * to {@code User.phone} only.
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
