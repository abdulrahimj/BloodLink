package com.bloodlink.domain;

import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link BloodType} as its clinical label ("A+", "O-", ...) so the
 * stored values match CLAUDE.md exactly. Applied automatically to every
 * BloodType attribute.
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
