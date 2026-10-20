package com.bloodlink.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bloodlink.config.WebSocketConfig;
import com.bloodlink.notification.dto.NotificationResponse;

/**
 * Pushes one notification to a user's open sockets, deferred until the transaction commits so a rolled-back
 * event is never announced. Failures are logged, not thrown: the Notification row is the durable copy.
 */
@Component
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    NotificationPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Delivers to the recipient's open sessions once the transaction commits;
     * nobody connected means nothing is sent, which is normal.
     */
    public void publish(UUID recipientUserId, NotificationResponse payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(recipientUserId, payload);
                }
            });
            return;
        }

        // No transaction in progress — nothing to wait for, so send immediately.
        send(recipientUserId, payload);
    }

    private void send(UUID recipientUserId, NotificationResponse payload) {
        try {
            // Resolved by Spring against the StompPrincipal name set at CONNECT,
            // i.e. this user's id, into the private queue of each of their sessions.
            messagingTemplate.convertAndSendToUser(
                recipientUserId.toString(), WebSocketConfig.USER_NOTIFICATION_QUEUE, payload
            );
        } catch (MessagingException ex) {
            log.warn("Could not push notification {} to user {} — it remains readable over REST.",
                payload.id(), recipientUserId, ex);
        }
    }
}
