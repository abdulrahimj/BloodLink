package com.bloodlink.donor.dto;

import java.util.UUID;

/**
 * The answer to a manual blood group correction (Business Rule 28): the donor's group before and after.
 */
public record DonorBloodGroupResponse(
    UUID donorId,
    String fullName,
    /** Null when the profile had no group before. */
    String previousBloodType,
    String bloodType
) {}
