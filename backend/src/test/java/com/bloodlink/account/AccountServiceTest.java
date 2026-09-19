package com.bloodlink.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.bloodlink.account.dto.AccountResponse;
import com.bloodlink.account.dto.ChangePasswordPayload;
import com.bloodlink.account.dto.UpdateAccountPayload;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.IncorrectCurrentPasswordException;
import com.bloodlink.web.error.InvalidFieldException;

/**
 * Business Rule 16 at the service: what a signed-in user may change about their own
 * account, and what proof a password change needs.
 *
 * Runs the real BCryptPasswordEncoder, not a mock. Every password assertion here is
 * "does this plaintext verify against that stored hash", and a mocked encoder would
 * answer whatever the test told it to — which is exactly how a check that never
 * really compares anything passes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceTest {

    private static final String CURRENT_PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "a-brand-new-passphrase";
    private static final String PHONE = "+23276900005";

    @Mock private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AccountService service;
    private User account;

    @BeforeEach
    void setUp() {
        service = new AccountService(userRepository, passwordEncoder);

        account = new User();
        account.setId(UUID.randomUUID());
        account.setFullName("Isatu Bangura");
        account.setEmail("isatu@example.com");
        account.setPhone(PHONE);
        account.setRole(Role.ADMIN);
        account.setPasswordHash(passwordEncoder.encode(CURRENT_PASSWORD));

        when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UpdateAccountPayload details(String fullName, String email) {
        return new UpdateAccountPayload(fullName, email, null);
    }

    private static UpdateAccountPayload details(String fullName, String email, Map<String, Object> extra) {
        return new UpdateAccountPayload(fullName, email, extra);
    }

    private static ChangePasswordPayload password(String current, String next) {
        return new ChangePasswordPayload(current, next, null);
    }

    // ---------- changing the password ----------

    @Test
    void theCorrectCurrentPasswordAllowsTheChange() {
        service.changeMyPassword(account.getId(), password(CURRENT_PASSWORD, NEW_PASSWORD));

        // The stored hash now verifies the new password and no longer the old one —
        // checked with the same encoder login uses, not by comparing strings.
        assertThat(passwordEncoder.matches(NEW_PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, account.getPasswordHash())).isFalse();
        verify(userRepository).save(account);
    }

    @Test
    void anIncorrectCurrentPasswordIsRefusedAndThePasswordIsNotChanged() {
        String hashBefore = account.getPasswordHash();

        assertThatThrownBy(() -> service.changeMyPassword(account.getId(), password("not-my-password", NEW_PASSWORD)))
            .isInstanceOf(IncorrectCurrentPasswordException.class)
            .hasMessageContaining("current password is incorrect");

        // Nothing written, and the old password still works — the whole point: a
        // session left open on a shared machine is not enough to take the account.
        assertThat(account.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, account.getPasswordHash())).isFalse();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void theCurrentPasswordIsComparedExactlyNotLoosely() {
        // BCrypt comparison is exact: a near-miss is simply wrong.
        assertThatThrownBy(() -> service.changeMyPassword(account.getId(), password(CURRENT_PASSWORD.toUpperCase(), NEW_PASSWORD)))
            .isInstanceOf(IncorrectCurrentPasswordException.class);
        assertThatThrownBy(() -> service.changeMyPassword(account.getId(), password(CURRENT_PASSWORD + " ", NEW_PASSWORD)))
            .isInstanceOf(IncorrectCurrentPasswordException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aNewPasswordIdenticalToTheCurrentOneIsRefused() {
        // Would "succeed" and change nothing — misleading for someone rotating a
        // password they think was seen.
        assertThatThrownBy(() -> service.changeMyPassword(account.getId(), password(CURRENT_PASSWORD, CURRENT_PASSWORD)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("newPassword");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aPhoneInAPasswordRequestIsRefusedAndNothingChanges() {
        String hashBefore = account.getPasswordHash();
        Map<String, Object> extra = Map.of("phone", "+23276111222");

        // Even with the correct current password: the request as a whole is refused.
        assertThatThrownBy(() -> service.changeMyPassword(account.getId(),
                new ChangePasswordPayload(CURRENT_PASSWORD, NEW_PASSWORD, extra)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("phone");

        assertThat(account.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(account.getPhone()).isEqualTo(PHONE);
        verify(userRepository, never()).save(any(User.class));
    }

    // ---------- editing name and email ----------

    @Test
    void anAdministratorCanEditTheirOwnNameAndEmail() {
        AccountResponse updated = service.updateMyDetails(account.getId(),
            details("  Isatu K. Bangura  ", "  Isatu.Bangura@Example.com "));

        // Normalised the way registration normalises: trimmed, email lower-cased.
        assertThat(updated.fullName()).isEqualTo("Isatu K. Bangura");
        assertThat(updated.email()).isEqualTo("isatu.bangura@example.com");
        assertThat(updated.role()).isEqualTo(Role.ADMIN);
        // And nothing else moved.
        assertThat(account.getPhone()).isEqualTo(PHONE);
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, account.getPasswordHash())).isTrue();
    }

    @Test
    void editingNameAndEmailNeedsNoCurrentPassword() {
        // Neither is a credential — nothing signs in with an email here — so there
        // is nothing to prove. Only the password change asks for the password.
        AccountResponse updated = service.updateMyDetails(account.getId(), details("New Name", "new@example.com"));

        assertThat(updated.fullName()).isEqualTo("New Name");
    }

    @Test
    void anEmailAlreadyUsedByAnotherAccountIsRefused() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.updateMyDetails(account.getId(), details("Isatu Bangura", "Taken@Example.com")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("email address");

        assertThat(account.getEmail()).isEqualTo("isatu@example.com");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void keepingYourOwnEmailIsNotAConflictWithYourself() {
        // The unique check only runs when the email actually changes — otherwise
        // renaming yourself would be refused because your own email "exists".
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        AccountResponse updated = service.updateMyDetails(account.getId(), details("Renamed", "ISATU@example.com"));

        assertThat(updated.fullName()).isEqualTo("Renamed");
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @ParameterizedTest(name = "\"{0}\" in a details request is refused, and nothing changes")
    @ValueSource(strings = { "phone", "role", "bloodBankId", "hospitalId", "status" })
    void anAdminManagedFieldInADetailsRequestIsRefusedAndNothingChanges(String field) {
        Map<String, Object> extra = new HashMap<>();
        extra.put(field, "anything");

        assertThatThrownBy(() -> service.updateMyDetails(account.getId(), details("Changed", "changed@example.com", extra)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo(field);

        // Refused whole: not "phone ignored, name saved" — the name did not move either.
        assertThat(account.getFullName()).isEqualTo("Isatu Bangura");
        assertThat(account.getEmail()).isEqualTo("isatu@example.com");
        assertThat(account.getPhone()).isEqualTo(PHONE);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aPhoneSetToNullIsStillAnAttemptAndIsRefused() {
        // {"phone": null} is an attempt to clear the phone, not an absence of one.
        Map<String, Object> extra = new HashMap<>();
        extra.put("phone", null);

        assertThatThrownBy(() -> service.updateMyDetails(account.getId(), details("Changed", "changed@example.com", extra)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("phone");

        assertThat(account.getPhone()).isEqualTo(PHONE);
    }

    @Test
    void thePhoneRefusalSaysWhyWithoutPromisingAFixThatDoesNotExist() {
        // Explains the reason (it is the login) and stops there: no endpoint lets
        // anyone edit a phone after creation, so the message must not send the user
        // off to ask for something nobody can do in the app.
        assertThatThrownBy(() -> service.updateMyDetails(account.getId(),
                details("X", "x@example.com", Map.of("phone", "+23276111222"))))
            .hasMessageContaining("how you sign in")
            .hasMessageContaining("cannot be changed here")
            .hasMessageNotContaining("administrator");
    }

    @Test
    void thePhoneIsReportedFirstWhenSeveralForbiddenFieldsAreSent() {
        Map<String, Object> extra = Map.of("role", "ADMIN", "phone", "+23276111222", "bloodBankId", UUID.randomUUID());

        assertThatThrownBy(() -> service.updateMyDetails(account.getId(), details("X", "x@example.com", extra)))
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("phone");
    }

    @Test
    void aPasswordSentToTheDetailsRequestIsRefusedAndPointsToTheRightForm() {
        // A password slipped in beside the name must not become a way round the
        // current-password check.
        String hashBefore = account.getPasswordHash();

        assertThatThrownBy(() -> service.updateMyDetails(account.getId(),
                details("X", "x@example.com", Map.of("newPassword", "sneaky-new-password"))))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessageContaining("fullName, email");

        assertThat(account.getPasswordHash()).isEqualTo(hashBefore);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void anEmptyCaptureOfUnknownFieldsIsFine() {
        // What a legitimate client produces: the any-setter map may be empty, not null.
        AccountResponse updated = service.updateMyDetails(account.getId(), details("Renamed", "isatu@example.com", Map.of()));

        assertThat(updated.fullName()).isEqualTo("Renamed");
    }

    // ---------- email by role (Business Rule 18) ----------

    @Test
    void aDonorWithNoEmailCanRenameThemselvesAndKeepNone() {
        account.setRole(Role.DONOR);
        account.setEmail(null);

        AccountResponse updated = service.updateMyDetails(account.getId(), details("Isatu Renamed", ""));

        assertThat(updated.fullName()).isEqualTo("Isatu Renamed");
        assertThat(updated.email()).isNull();
        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    void aDonorCanRemoveTheirEmail() {
        account.setRole(Role.DONOR);

        AccountResponse updated = service.updateMyDetails(account.getId(), details("Isatu Bangura", "   "));

        assertThat(updated.email()).isNull();
    }

    @Test
    void aDonorsNewEmailGetsTheSameFormatCheckAsRegistration() {
        account.setRole(Role.DONOR);

        assertThatThrownBy(() -> service.updateMyDetails(account.getId(), details("Isatu Bangura", "isatu@gmail")))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("email");

        assertThat(account.getEmail()).isEqualTo("isatu@example.com");
        verify(userRepository, never()).save(any(User.class));
    }

    @ParameterizedTest(name = "a {0} account must keep an email")
    @ValueSource(strings = { "ADMIN", "HOSPITAL_STAFF", "BLOOD_BANK_STAFF" })
    void staffAndAdministratorsMustKeepAnEmail(String role) {
        account.setRole(Role.valueOf(role));

        assertThatThrownBy(() -> service.updateMyDetails(account.getId(), details("Isatu Bangura", " ")))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessage("Email address is required");

        assertThat(account.getEmail()).isEqualTo("isatu@example.com");
        verify(userRepository, never()).save(any(User.class));
    }

    // ---------- reading ----------

    @Test
    void theAccountIsReadWithItsAdminManagedFactsForDisplay() {
        BloodBank bank = new BloodBank();
        bank.setId(UUID.randomUUID());
        bank.setName("Makeni Regional Blood Bank");
        account.setRole(Role.BLOOD_BANK_STAFF);
        account.setBloodBank(bank);

        AccountResponse read = service.getMyAccount(account.getId());

        assertThat(read.phone()).isEqualTo(PHONE);
        assertThat(read.role()).isEqualTo(Role.BLOOD_BANK_STAFF);
        assertThat(read.bloodBankName()).isEqualTo("Makeni Regional Blood Bank");
        assertThat(read.hospitalId()).isNull();
    }
}
