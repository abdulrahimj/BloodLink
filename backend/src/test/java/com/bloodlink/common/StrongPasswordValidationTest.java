package com.bloodlink.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.bloodlink.account.dto.ChangePasswordPayload;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.domain.enums.Gender;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

/** Registration and the password change (Profile Settings and the forced first sign-in) both apply PasswordPolicy. */
class StrongPasswordValidationTest {

    private static ValidatorFactory factory;

    @BeforeAll
    static void openFactory() {
        factory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static List<String> messagesFor(Object payload, String field) {
        Stream<? extends ConstraintViolation<?>> violations = factory.getValidator().validate(payload).stream();
        return violations.filter(v -> v.getPropertyPath().toString().equals(field)).map(ConstraintViolation::getMessage).toList();
    }

    private static RegisterRequest registration(String password) {
        return new RegisterRequest("Aminata Sesay", "076900001", null, LocalDate.of(1998, 3, 12), Gender.FEMALE, "O+",
            "Wusum, Makeni", password);
    }

    private static ChangePasswordPayload change(String newPassword) {
        return new ChangePasswordPayload("Old-password-1", newPassword, Map.of());
    }

    @Test
    void registrationNamesExactlyWhatIsMissing() {
        assertThat(messagesFor(registration("Password123!"), "password")).isEmpty();
        assertThat(messagesFor(registration("password123"), "password"))
            .containsExactly("Add a capital letter and a special character.");
        assertThat(messagesFor(registration("Aa1!" + "x".repeat(69)), "password"))
            .containsExactly("Use no more than 72 characters.");
    }

    @Test
    void thePasswordChangeNamesExactlyWhatIsMissing() {
        assertThat(messagesFor(change("New-password-2"), "newPassword")).isEmpty();
        assertThat(messagesFor(change("Abc1!"), "newPassword")).containsExactly("Use at least 8 characters.");
        assertThat(messagesFor(change("NEWPASSWORD-2"), "newPassword")).containsExactly("Add a small letter.");
    }

    @Test
    void aBlankPasswordGetsOnlyTheRequiredMessage() {
        assertThat(messagesFor(registration(""), "password")).containsExactly("Password is required");
        assertThat(messagesFor(change("   "), "newPassword")).containsExactly("Enter a new password");
    }
}
