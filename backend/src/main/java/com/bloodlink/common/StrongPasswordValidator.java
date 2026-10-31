package com.bloodlink.common;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Applies PasswordPolicy and reports its own sentence (fixed text, never the password) as the field's message. */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String problem = PasswordPolicy.problemWith(value);
        if (problem == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
        return false;
    }
}
