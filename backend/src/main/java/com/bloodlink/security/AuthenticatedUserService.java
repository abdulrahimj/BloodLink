package com.bloodlink.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.repository.UserRepository;

/**
 * Loads an account by phone for Spring Security's password check at login only;
 * authenticated requests rebuild the principal from the token.
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
