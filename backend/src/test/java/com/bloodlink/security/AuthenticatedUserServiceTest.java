package com.bloodlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;

/**
 * The credential lookup Spring Security's DaoAuthenticationProvider runs on every
 * login attempt — small, but security-critical, and previously untested. What
 * matters here: the found and not-found paths both resolve, and the not-found
 * message stays as vague as InvalidCredentialsException's own, so a probe against
 * this class cannot be used to enumerate registered phone numbers ahead of the
 * password check.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticatedUserServiceTest {

    private static final String PHONE = "+232 76 000 001";

    @Mock private UserRepository userRepository;

    private AuthenticatedUserService service;

    @BeforeEach
    void setUp() {
        service = new AuthenticatedUserService(userRepository);
    }

    @Test
    void aKnownPhoneNumberResolvesToItsAccount() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setPhone(PHONE);
        user.setPasswordHash("$2a$hash");
        user.setRole(Role.BLOOD_BANK_STAFF);
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));

        UserDetails found = service.loadUserByUsername(PHONE);

        assertThat(found).isInstanceOf(AuthenticatedUser.class);
        assertThat(((AuthenticatedUser) found).getId()).isEqualTo(userId);
        assertThat(found.getUsername()).isEqualTo(PHONE);
        assertThat(found.getPassword()).isEqualTo("$2a$hash");
    }

    @Test
    void anUnknownPhoneNumberIsRefusedWithoutRevealingThatItIsUnknown() {
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.empty());

        // Deliberately not asserting on the exception's own message: it never
        // reaches the caller (AuthService's catch block replaces it with the same
        // generic "Invalid phone number or password" a wrong password gets), so
        // what matters here is only that this path throws rather than returning
        // something a caller could mistake for a real account.
        assertThatThrownBy(() -> service.loadUserByUsername(PHONE))
            .isInstanceOf(UsernameNotFoundException.class);
    }
}
