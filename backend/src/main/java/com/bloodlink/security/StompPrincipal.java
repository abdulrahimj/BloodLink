package com.bloodlink.security;

import java.security.Principal;
import java.util.UUID;

import com.bloodlink.domain.enums.Role;

/**
 * Identifies the account behind one WebSocket session.
 *
 * Spring routes a server-to-one-client message by {@link #getName()}: sending to
 * user "9f3c…" reaches whichever sessions were opened by a principal with exactly
 * that name. The name here is therefore the user's UUID as text — the same id the
 * JWT carries as its subject and the same one the Notification rows are written
 * against — so a notification cannot be delivered to the wrong session because two
 * accounts happened to share a phone number or a display name.
 *
 * Deliberately not reusing {@link AuthenticatedUser}: that is a Spring Security
 * UserDetails whose "username" is the login phone number, which is the wrong
 * routing key here and would silently become one if it were used.
 */
public record StompPrincipal(UUID userId, Role role) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
