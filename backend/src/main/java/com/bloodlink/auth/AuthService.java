package com.bloodlink.auth;

import java.time.Instant;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.common.EmailAddress;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.security.JwtService;
import com.bloodlink.web.error.AccountSuspendedException;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidCredentialsException;

/**
 * The two entry points into the system: creating a donor account, and exchanging
 * credentials for an access token.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final DonorRepository donorRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    AuthService(
        UserRepository userRepository,
        DonorRepository donorRepository,
        PasswordEncoder passwordEncoder,
        AuthenticationManager authenticationManager,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.donorRepository = donorRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Registers a donor and signs them in: User and Donor rows in one transaction, role always DONOR.
     * Phone via PhoneNumber.normalize (as login does), optional email via EmailAddress.normalizeOptional, before checks.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Also the format gate: an unusable number is a 400 naming the phone field,
        // raised before any row is written, rather than an account nobody can sign into.
        String phone = PhoneNumber.normalize(request.phone());
        // Optional (Business Rule 18): null when blank, else trimmed, lower-cased and format-checked (400 on email).
        String email = EmailAddress.normalizeOptional(request.email());

        // Checked up front so a duplicate is an actionable 409 rather than a 500;
        // the unique constraints remain the real guarantee under concurrency.
        if (userRepository.existsByPhone(phone)) {
            throw new ConflictException("An account already exists for this phone number.");
        }
        // Only an address that exists can clash; no email is never a conflict, so the check is skipped.
        if (email != null && userRepository.existsByEmail(email)) {
            throw new ConflictException("This email is already registered");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhone(phone);
        // BCrypt: salted and deliberately slow, so the stored value is never reversible.
        user.replacePassword(passwordEncoder.encode(request.password()), Instant.now());
        user.setRole(Role.DONOR);
        // Reassigned from save(): the generated UUID and created_at are only present
        // on the returned instance, and the token issued below needs the id.
        user = userRepository.save(user);

        Donor donor = new Donor();
        donor.setUser(user);
        donor.setBloodType(parseBloodType(request.bloodType()));
        donor.setDateOfBirth(request.dateOfBirth());
        donor.setGender(request.gender());
        donor.setAddress(request.address().trim());
        // lastDonationDate stays null: a new donor has never donated, which
        // Business Rule 2 already reads as eligible.
        donorRepository.save(donor);

        return AuthResponse.of(jwtService.issueToken(user), jwtService.getExpirySeconds(), user);
    }

    /**
     * Verifies phone and password and returns a token; the phone is normalised exactly as registration stored it.
     * Status is checked only after the password is proven, so a suspended account cannot be detected without it.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String phone = PhoneNumber.normalize(request.phone());

        try {
            var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(phone, request.password())
            );

            // The provider has already loaded and verified the account; re-read it as an
            // entity so the response can carry the full name and email for the sidebar.
            AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
            User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid phone number or password."));

            if (user.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountSuspendedException(
                    "This account has been suspended. Contact an administrator."
                );
            }

            return AuthResponse.of(jwtService.issueToken(user), jwtService.getExpirySeconds(), user);

        } catch (AuthenticationException ex) {
            // One message for both "unknown phone" and "wrong password", so the response
            // cannot be used to discover which phone numbers are registered.
            throw new InvalidCredentialsException("Invalid phone number or password.");
        }
    }

    /**
     * Maps the wire label ("A+") to BloodType; blank means "I don't know yet", stored as null
     * until a tested donation establishes it (Business Rule 4).
     */
    private BloodType parseBloodType(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        // Propagates IllegalArgumentException for an unrecognised label, which the
        // global handler turns into a 400 rather than a 500.
        return BloodType.fromLabel(label.trim());
    }
}
