package com.bloodlink.bloodbank.dto;

import java.util.UUID;

/** One row of GET /api/blood-banks — reference data, not self-registering. */
public record BloodBankResponse(
    UUID id,
    String name,
    String location,
    String contactPhone
) {}
