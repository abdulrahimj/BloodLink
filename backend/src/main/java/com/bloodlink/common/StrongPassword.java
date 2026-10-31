package com.bloodlink.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A password being chosen must meet PasswordPolicy; the 400 names exactly what is missing. Null or blank is left to
 * {@code @NotBlank}, so an empty field gets one clear "required" message.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "This password does not meet the password rule.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
