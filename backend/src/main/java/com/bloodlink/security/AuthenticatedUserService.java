package com.bloodlink.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.repository.UserRepository;

/**
 * Loads an account for Spring Security's password check at login time.
 *
 * Used only by the login flow — authenticated requests rebuild their principal from
 * the token's claims instead, so this is not on the per-request hot path.
 *
 * The "username" is the phone number, matching the Login screen's single credential
 * field; V2 made users.phone unique so this lookup can only ever match one account.
 */
@Service
public class AuthenticatedUserService implements UserDetailsService {

    private final UserRepository userRepository;

    AuthenticatedUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        return userRepository.findByPhone(phone)
            .map(AuthenticatedUser::from)
            // Deliberately vague: the caller only ever sees "Invalid phone number or
            // password", so an attacker cannot use the error to enumerate accounts.
            .orElseThrow(() -> new UsernameNotFoundException("No account for the supplied phone number"));
    }
}
