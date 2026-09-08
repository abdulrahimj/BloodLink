package com.bloodlink.hospital.dto;

import java.util.UUID;

/** One row of GET /api/hospitals — reference data, not self-registering (V6). */
public record HospitalResponse(
    UUID id,
    String name,
    String location,
    String contactPhone
) {}
