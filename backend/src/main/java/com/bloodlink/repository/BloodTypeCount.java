package com.bloodlink.repository;

import com.bloodlink.domain.enums.BloodType;

/**
 * One row of a "how many per blood group" aggregate — a GROUP BY blood_type query.
 * Shared by the requests, donations and donors repositories behind the Admin Reports
 * page (Business Rule 23). {@code getBloodType()} is null for donors who registered
 * without knowing their group.
 */
public interface BloodTypeCount {
    BloodType getBloodType();
    long getTotal();
}
