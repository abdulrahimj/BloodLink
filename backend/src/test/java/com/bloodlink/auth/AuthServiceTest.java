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
 * Registration rules that must hold regardless of the database: the role is never
 * client-controlled, the password is never stored in the clear, the Donor row is
 * always created alongside the User, and a duplicate is refused.
 *
 * Also covers the one rule login must add on top of a correct password: a
 * suspended account is refused even once the password has checked out, with a
 * message that — unlike a failed login — is allowed to say so plainly.
 *
 * And it covers the rule that binds the two halves of this class together: phone is
 * the login credential, matched exactly, so registration and login must normalise it
 * identically. {@link #aDonorWhoRegisteredLocallyCanSignInWithTheInternationalFormat}
 * is the one that would fail if those two ever drifted apart — the failure being a
 * real donor permanently locked out of an account they successfully created. The
 * format rule itself is tested on its own in {@code PhoneNumberTest}; what is tested
 * here is that both entry points actually apply it.
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

    /** The same registration, with the phone number written however the test needs
     *  it — the point of several tests below is that the way it is typed does not
     *  change what gets stored. */
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
            "correct-horse-battery"
        );
    }

    @Test
    void registrationStoresBcryptHashAndNeverThePlainPassword() {
        newService().register(request("O+"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getPasswordHash())
            .isNotEqualTo("correct-horse-battery")
            .startsWith("$2");
        assertThat(passwordEncoder.matches("correct-horse-battery", saved.getPasswordHash())).isTrue();
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
        // Typed the local way; stored the one way. Phone is the login credential and
        // is matched exactly, so the format written here is the format that has to be
        // reproduced at every future sign-in — which is why it is not left as typed.
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
        // Already registered, in the stored form. Someone signing up with the same
        // number typed the local way must still be caught as a duplicate — checking
        // the raw string would let one person hold two accounts on one number.
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
        return new LoginRequest("+232 76 000 001", "correct-horse-battery");
    }

    /** Mocks the AuthenticationManager step so login() proceeds as if the
     *  password were already verified — that verification is Spring Security's
     *  job, not AuthService's, and is not what these tests are about. */
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

        service.login(new LoginRequest("076 900 005", "correct-horse-battery"));

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
        service.login(new LoginRequest("+232 76 900 005", "correct-horse-battery"));

        // 3. The number looked up is character-for-character the number stored at
        //    registration. This is the whole rule in one assertion: if these two ever
        //    disagree, a real donor is locked out of an account they created.
        ArgumentCaptor<UsernamePasswordAuthenticationToken> attempted =
            ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(attempted.capture());

        assertThat(attempted.getValue().getPrincipal()).isEqualTo(storedPhone);
    }

    @Test
    void aLoginPhoneThatIsNotAValidNumberIsRefusedWithoutTouchingTheAuthenticationManager() {
        AuthService service = newService();

        assertThatThrownBy(() -> service.login(new LoginRequest("07690000a", "correct-horse-battery")))
            .isInstanceOf(InvalidPhoneNumberException.class);

        // A 400 about the shape of the input, raised before any account is looked up,
        // so it reveals nothing about which numbers are registered — and tells the
        // person about the typo rather than blaming a password that was fine.
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void loginRefusesASuspendedAccountEvenWithTheCorrectPassword() {
        AuthService service = newService();
        stubSuccessfulAuthentication(authenticatedUser(AccountStatus.SUSPENDED));

        // The password was right — AuthenticationManager already said so — so this
        // must be the specific, plainer exception, not the vague InvalidCredentials
        // one a wrong password produces. See AccountSuspendedException for why
        // being specific here does not leak anything a wrong-password attempt would.
        assertThatThrownBy(() -> service.login(loginRequest()))
            .isInstanceOf(AccountSuspendedException.class)
            .hasMessageContaining("suspended");

        verify(jwtService, never()).issueToken(any(User.class));
    }
}
