package com.bloodlink.repository;

import com.bloodlink.domain.enums.BloodType;

/**
 * One row of a per-blood-group count (GROUP BY blood_type), shared by the Admin Reports repositories
 * (Business Rule 23); getBloodType() is null for untyped donors.
 */
public interface BloodTypeCount {
    BloodType getBloodType();
    long getTotal();
}
