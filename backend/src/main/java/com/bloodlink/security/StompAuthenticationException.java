package com.bloodlink.security;

import org.springframework.messaging.MessagingException;

/**
 * Refuses a STOMP CONNECT with a missing, expired or unreadable token;
 * a MessagingException so the client gets a STOMP ERROR frame.
 */
public class StompAuthenticationException extends MessagingException {

    public StompAuthenticationException(String message) {
        super(message);
    }
}
