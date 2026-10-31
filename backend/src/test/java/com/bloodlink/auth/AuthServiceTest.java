package com.bloodlink.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.security.JwtService;
import com.bloodlink.web.error.AccountSuspendedException;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.InvalidPhoneNumberException;

/**
 * Registration rules (role never client-chosen, password hashed, Donor row created, duplicates refused),
 * login's suspension check, and that both normalise the phone identically.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private DonorRepository donorRepository;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService newService() {
        // save() stands in for the database assigning the primary key, which the
        // issued token's subject depends on.
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(jwtService.issueToken(any(User.class))).thenReturn("signed-token");
        when(jwtService.getExpirySeconds()).thenReturn(86_400L);

        return new AuthService(userRepository, donorRepository, passwordEncoder, authenticationManager, jwtService);
    }

    private static RegisterRequest request(String bloodTypeLabel) {
        return request(bloodTypeLabel, "+232 76 000 001");
    }

    /** The same registration with the phone typed however the test needs it. */
    private static RegisterRequest request(String bloodTypeLabel, String phone) {
        return withEmail(bloodTypeLabel, phone, "Aminata@Example.com");
    }

    /** A registration with the email given exactly as the test needs — including
     *  none at all, which Business Rule 18 allows. */
    private static RegisterRequest withEmail(String bloodTypeLabel, String phone, String email) {
        return new RegisterRequest(
            "Aminata Kamara",
            phone,
            email,
            LocalDate.of(2000, 1, 1),
            Gender.FEMALE,
            bloodTypeLabel,
            "Wusum, Makeni",
            "Correct-horse-battery-9"
        );
    }

    @Test
    void registrationStoresBcryptHashAndNeverThePlainPassword() {
        newService().register(request("O+"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getPasswordHash())
            .isNotEqualTo("Correct-horse-battery-9")
            .startsWith("$2");
        assertThat(passwordEncoder.matches("Correct-horse-battery-9", saved.getPasswordHash())).isTrue();
    }

    @Test
    void selfRegistrationAlwaysProducesADonorAccount() {
        newService().register(request("O+"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        // The endpoint is public, so the role must come from the server, never the
        // request — otherwise anyone could register themselves as an administrator.
        assertThat(captor.getValue().getRole()).isEqualTo(Role.DONOR);
    }

    @Test
    void registrationCreatesTheLinkedDonorProfile() {
        newService().register(request("O+"));

        ArgumentCaptor<Donor> captor = ArgumentCaptor.forClass(Donor.class);
        verify(donorRepository).save(captor.capture());
        Donor donor = captor.getValue();

        assertThat(donor.getUser()).isNotNull();
        assertThat(donor.getBloodType()).isEqualTo(BloodType.O_POSITIVE);
        assertThat(donor.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(donor.getAddress()).isEqualTo("Wusum, Makeni");
        // Never donated, which Business Rule 2 reads as eligible.
        assertThat(donor.getLastDonationDate()).isNull();
    }

    @Test
    void donorWhoDoesNotKnowTheirBloodGroupIsStoredWithNone() {
        newService().register(request(null));

        ArgumentCaptor<Donor> captor = ArgumentCaptor.forClass(Donor.class);
        verify(donorRepository).save(captor.capture());

        assertThat(captor.getValue().getBloodType()).isNull();
    }

    @Test
    void emailIsNormalisedSoCaseVariantsCannotRegisterTwice() {
        newService().register(request("O+"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().getEmail()).isEqualTo("aminata@example.com");
    }

    @Test
    void phoneIsNormalisedSoTheFormatItWasTypedInCannotLockTheDonorOut() {
        // Typed the local way, stored in the one format every future sign-in must reproduce.
        newService().register(request("O+", "076 900 005"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().getPhone()).isEqualTo("+23276900005");
    }

    @Test
    void anInternationalPhoneNumberIsStoredInTheSameFormAsALocalOne() {
        newService().register(request("O+", "+232 76 900 005"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().getPhone()).isEqualTo("+23276900005");
    }

    @Test
    void theDuplicateCheckRunsAgainstTheNormalisedNumberNotTheTypedOne() {
        AuthService service = newService();
        // Already stored in normalised form, so the same number typed locally must still be a duplicate.
        when(userRepository.existsByPhone("+23276900005")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("O+", "076900005")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("phone number");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aPhoneNumberThatIsNotAValidNumberIsRefusedBeforeAnythingIsWritten() {
        AuthService service = newService();

        // Wrong length, though every character is a digit — the case that would
        // otherwise create an account no one could ever sign in to.
        assertThatThrownBy(() -> service.register(request("O+", "07690000")))
            .isInstanceOf(InvalidPhoneNumberException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(donorRepository, never()).save(any(Donor.class));
    }

    @Test
    void duplicatePhoneIsRefusedAndNothingIsWritten() {
        AuthService service = newService();
        when(userRepository.existsByPhone(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("O+")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("phone number");

        verify(userRepository, never()).save(any(User.class));
        verify(donorRepository, never()).save(any(Donor.class));
    }

    @Test
    void duplicateEmailIsRefusedAndNothingIsWritten() {
        AuthService service = newService();
        // Compared after lower-casing: the stored address and the typed one differ
        // only in case, and are still the same address.
        when(userRepository.existsByEmail("aminata@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("O+")))
            .isInstanceOf(ConflictException.class)
            .hasMessage("This email is already registered");

        verify(userRepository, never()).save(any(User.class));
        verify(donorRepository, never()).save(any(Donor.class));
    }

    // ---------- optional, format-checked email (Business Rule 18) ----------

    @Test
    void aDonorCanRegisterWithNoEmail() {
        newService().register(withEmail("O+", "+232 76 000 001", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().getEmail()).isNull();
        // No address, nothing to be a duplicate of — the database is not even asked.
        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    void aBlankEmailIsStoredAsNoEmailNotAsAnEmptyString() {
        // An empty string would collide with the next donor who also left it blank;
        // NULL never collides with anything (V13).
        newService().register(withEmail("O+", "+232 76 000 001", "   "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().getEmail()).isNull();
        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    void aMalformedEmailIsRefusedOnTheEmailFieldAndNothingIsWritten() {
        AuthService service = newService();

        // The typo this check exists for: a domain with no ending. Bean Validation's
        // own @Email would have let it through.
        assertThatThrownBy(() -> service.register(withEmail("O+", "+232 76 000 001", "aminata@gmail")))
            .isInstanceOf(InvalidFieldException.class)
            .satisfies(ex -> assertThat(((InvalidFieldException) ex).getField()).isEqualTo("email"));

        verify(userRepository, never()).save(any(User.class));
        verify(donorRepository, never()).save(any(Donor.class));
    }

    // ---------- login ----------

    private static final UUID USER_ID = UUID.randomUUID();

    /** Stands in for what DaoAuthenticationProvider hands back once the password
     *  has already checked out — login() is never reached without this. */
    private static User authenticatedUser(AccountStatus status) {
        User user = new User();
        user.setId(USER_ID);
        user.setFullName("Aminata Kamara");
        user.setEmail("aminata@example.com");
        user.setPhone("+232 76 000 001");
        user.setRole(Role.DONOR);
        user.setStatus(status);
        return user;
    }

    private static LoginRequest loginRequest() {
        return new LoginRequest("+232 76 000 001", "Correct-horse-battery-9");
    }

    /** Mocks the AuthenticationManager so login() proceeds as if the password were verified (Spring Security's job). */
    private void stubSuccessfulAuthentication(User user) {
        AuthenticatedUser principal = AuthenticatedUser.from(user);
        when(authenticationManager.authenticate(any()))
            .thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void loginIssuesATokenForAnActiveAccount() {
        AuthService service = newService();
        stubSuccessfulAuthentication(authenticatedUser(AccountStatus.ACTIVE));

        var response = service.login(loginRequest());

        assertThat(response.token()).isEqualTo("signed-token");
        assertThat(response.user().id()).isEqualTo(USER_ID);
    }

    @Test
    void loginNormalisesThePhoneBeforeItIsComparedAgainstStoredAccounts() {
        AuthService service = newService();
        stubSuccessfulAuthentication(authenticatedUser(AccountStatus.ACTIVE));

        service.login(new LoginRequest("076 900 005", "Correct-horse-battery-9"));

        // What reaches Spring Security — and therefore the users.phone lookup behind
        // it — is the stored form, not the spaced local form that was typed.
        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
            ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getPrincipal()).isEqualTo("+23276900005");
    }

    @Test
    void aDonorWhoRegisteredLocallyCanSignInWithTheInternationalFormat() {
        AuthService service = newService();

        // 1. Register the way a donor in Makeni types their own number.
        service.register(request("O+", "076900005"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        String storedPhone = saved.getValue().getPhone();

        // 2. Come back later and sign in the way the form's placeholder shows it,
        //    spaces and country code and all — a different string entirely.
        stubSuccessfulAuthentication(authenticatedUser(AccountStatus.ACTIVE));
        service.login(new LoginRequest("+232 76 900 005", "Correct-horse-battery-9"));

        // 3. The number looked up matches the stored one exactly; if they differ, a donor is locked out.
        ArgumentCaptor<UsernamePasswordAuthenticationToken> attempted =
            ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(attempted.capture());

        assertThat(attempted.getValue().getPrincipal()).isEqualTo(storedPhone);
    }

    @Test
    void aLoginPhoneThatIsNotAValidNumberIsRefusedWithoutTouchingTheAuthenticationManager() {
        AuthService service = newService();

        assertThatThrownBy(() -> service.login(new LoginRequest("07690000a", "Correct-horse-battery-9")))
            .isInstanceOf(InvalidPhoneNumberException.class);

        // A 400 about the input's shape, raised before any lookup, so it reveals nothing about registered numbers.
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void loginRefusesASuspendedAccountEvenWithTheCorrectPassword() {
        AuthService service = newService();
        stubSuccessfulAuthentication(authenticatedUser(AccountStatus.SUSPENDED));

        // The password was right, so this must be AccountSuspendedException, not the vague InvalidCredentials one.
        assertThatThrownBy(() -> service.login(loginRequest()))
            .isInstanceOf(AccountSuspendedException.class)
            .hasMessageContaining("suspended");

        verify(jwtService, never()).issueToken(any(User.class));
    }
}
