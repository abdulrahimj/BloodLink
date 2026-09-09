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
 * Pushes one notification to one signed-in user's open sockets.
 *
 * Two things make this more than a one-line call to SimpMessagingTemplate.
 *
 * First, timing. Notifications are created inside the same database transaction as
 * the event that caused them — fulfilling a request writes the deduction, the new
 * status and the alert together — and that transaction can still fail after the
 * alert has been built. A push cannot be taken back, so it is deferred until the
 * transaction has actually committed. Without that, a bank could be told stock had
 * fallen in a fulfilment that was then rolled back and never happened.
 *
 * Second, failure. A WebSocket push is best-effort by design: the durable copy is
 * the Notification row, and a client that missed the push reads it from
 * GET /api/notifications on its next load. So a delivery failure is logged and
 * swallowed rather than thrown — letting it propagate would turn "the alert did not
 * reach a screen" into "the fulfilment failed", which is far worse and quite wrong.
 */
@Component
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    NotificationPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Delivers to whichever sessions {@code recipientUserId} currently has open,
     * once the surrounding transaction commits. Nobody connected means nothing is
     * sent and nothing is wrong — that is the ordinary case for a donor who is not
     * looking at the app.
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
