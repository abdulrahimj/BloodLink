package com.bloodlink.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.JwtService;
import com.bloodlink.web.error.ConflictException;

/**
 * Registration rules that must hold regardless of the database: the role is never
 * client-controlled, the password is never stored in the clear, the Donor row is
 * always created alongside the User, and a duplicate is refused.
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
        return new RegisterRequest(
            "Aminata Kamara",
            "+232 76 000 001",
            "Aminata@Example.com",
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
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("O+")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("email address");

        verify(userRepository, never()).save(any(User.class));
        verify(donorRepository, never()).save(any(Donor.class));
    }
}
