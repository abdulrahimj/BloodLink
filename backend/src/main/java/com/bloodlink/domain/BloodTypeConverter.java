package com.bloodlink.domain;

import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists BloodType as its clinical label ("A+", "O-"), applied automatically to every BloodType attribute.
 */
@Converter(autoApply = true)
public class BloodTypeConverter implements AttributeConverter<BloodType, String> {

    @Override
    public String convertToDatabaseColumn(BloodType attribute) {
        return attribute == null ? null : attribute.getLabel();
    }

    @Override
    public BloodType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : BloodType.fromLabel(dbData);
    }
}
