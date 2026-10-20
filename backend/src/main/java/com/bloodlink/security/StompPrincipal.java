package com.bloodlink.security;

import java.security.Principal;
import java.util.UUID;

import com.bloodlink.domain.enums.Role;

/**
 * The account behind one WebSocket session; its name is the user's UUID, the key pushes are routed by,
 * not AuthenticatedUser's phone-number username.
 */
public record StompPrincipal(UUID userId, Role role) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
