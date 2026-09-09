package com.bloodlink.security;

import org.springframework.messaging.MessagingException;

/**
 * Refuses a STOMP CONNECT whose token is missing, expired or unreadable.
 *
 * Extends MessagingException because that is what the messaging channel knows how
 * to turn into a STOMP ERROR frame and a closed session; a plain RuntimeException
 * would reach the client as an opaque transport failure with nothing to show the
 * user. GlobalExceptionHandler is not involved — nothing on this path is an HTTP
 * request by the time the frame is read.
 */
public class StompAuthenticationException extends MessagingException {

    public StompAuthenticationException(String message) {
        super(message);
    }
}
