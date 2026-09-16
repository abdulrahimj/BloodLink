package com.bloodlink.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
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
     * Registers a voluntary donor and signs them straight in.
     *
     * Writes two rows that must both exist or neither: the User (credentials and role)
     * and the linked Donor profile (clinical and demographic details). They are a
     * 1–1 pair, and a User with role DONOR but no Donor row would break every donor
     * screen, so the whole method is one transaction.
     *
     * The role is hard-coded to DONOR rather than taken from the request — this is a
     * public, unauthenticated endpoint, so honouring a client-supplied role would let
     * anyone create an administrator. Staff accounts are created by an administrator.
     *
     * A token is issued on success so the new donor lands on their dashboard instead
     * of being bounced to the login screen to re-enter what they just typed.
     *
     * Phone and email are both normalised before anything is checked or written, for
     * the same reason: each is a unique identifier compared by exact string equality,
     * so the stored spelling decides whether a later match succeeds. Email is lower-cased;
     * phone goes through {@link PhoneNumber#normalize} — the same call login makes, so
     * the value written here is character-for-character the value login will compare
     * against, whichever of the two accepted formats either was typed in.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Also the format gate: an unusable number is a 400 naming the phone field,
        // raised before any row is written, rather than an account nobody can sign into.
        String phone = PhoneNumber.normalize(request.phone());
        String email = request.email().trim().toLowerCase();

        // Checked up front so a duplicate returns an actionable 409 naming the field,
        // rather than a constraint violation surfacing from the database as a 500.
        // The unique constraints remain the real guarantee under concurrent signups.
        if (userRepository.existsByPhone(phone)) {
            throw new ConflictException("An account already exists for this phone number.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account already exists for this email address.");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhone(phone);
        // BCrypt: salted and deliberately slow, so the stored value is never reversible.
        user.setPasswordHash(passwordEncoder.encode(request.password()));
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
     * Verifies phone + password and returns a signed token.
     *
     * The phone is normalised first, through the very same {@link PhoneNumber#normalize}
     * call registration made before storing it. That is what lets a donor who signed up
     * typing {@code 076900005} sign in later typing {@code +232 76 900 005}: both become
     * {@code +23276900005}, and the lookup that follows is comparing one spelling against
     * one spelling. Normalising here rather than inside AuthenticatedUserService keeps it
     * on the path the user's own input takes, and does it once.
     *
     * The password comparison is delegated to Spring Security's AuthenticationManager
     * (DaoAuthenticationProvider + BCrypt) rather than done by hand, so it benefits
     * from the framework's constant-time comparison and its protection against timing
     * differences between "no such user" and "wrong password".
     *
     * The suspension check happens after that authentication succeeds, and
     * deliberately not before it: checking status first would mean a caller who
     * does not even know the password can tell a suspended account from an active
     * one purely by which error comes back, which is exactly the kind of account
     * enumeration InvalidCredentialsException's single vague message exists to
     * prevent. Once the password is proven correct, there is nothing left to
     * protect by staying vague, so AccountSuspendedException is free to say
     * plainly what happened.
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
     * Maps the wire label ("A+", "O-") to the BloodType enum.
     * Absent or blank means the donor chose "I don't know yet" — stored as null and
     * established later from a tested Donation (Business Rule 4).
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
