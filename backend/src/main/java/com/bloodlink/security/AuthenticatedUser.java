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
 * The SecurityContext principal: carries the user's UUID (needed on every request) as well as the role,
 * whose single authority "ROLE_" + name makes hasRole() work.
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
