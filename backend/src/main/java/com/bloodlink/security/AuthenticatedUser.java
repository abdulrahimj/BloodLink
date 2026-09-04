package com.bloodlink.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;

/**
 * The principal placed in the SecurityContext for an authenticated request.
 *
 * Spring's own UserDetails only carries a username string; this project needs the
 * user's UUID on every request (to scope a donor to their own records, or staff to
 * their own blood bank), so the id is carried here instead of being looked up again.
 *
 * The single authority is "ROLE_" + the Role enum name, which is what lets
 * hasRole('DONOR') and friends work in SecurityConfig without further mapping.
 */
public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final String phone;
    private final String passwordHash;
    private final Role role;

    public AuthenticatedUser(UUID id, String phone, String passwordHash, Role role) {
        this.id = id;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /** Builds a principal from a persisted user — the path used at login. */
    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getPhone(), user.getPasswordHash(), user.getRole());
    }

    public UUID getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Phone doubles as the username, because phone is the login credential. */
    @Override
    public String getUsername() {
        return phone;
    }
}
