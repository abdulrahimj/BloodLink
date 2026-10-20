package com.bloodlink.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bloodlink.config.WebSocketConfig;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.notification.dto.NotificationResponse;

/**
 * A push made mid-transaction waits for commit, and a delivery failure is swallowed
 * rather than failing the write, tested directly.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

    private static final UUID RECIPIENT_ID = UUID.randomUUID();

    @Mock private SimpMessagingTemplate messagingTemplate;

    @AfterEach
    void clearAnyOpenSynchronization() {
        // Defensive: never leave transaction synchronization active for the next test on this thread.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static NotificationResponse payload() {
        return new NotificationResponse(
            UUID.randomUUID(), "Test message", NotificationType.LOW_STOCK_ALERT, false, false, Instant.now()
        );
    }

    @Test
    void withNoTransactionInProgressThePushIsSentImmediately() {
        NotificationPublisher publisher = new NotificationPublisher(messagingTemplate);
        NotificationResponse payload = payload();

        publisher.publish(RECIPIENT_ID, payload);

        verify(messagingTemplate).convertAndSendToUser(
            eq(RECIPIENT_ID.toString()), eq(WebSocketConfig.USER_NOTIFICATION_QUEUE), eq(payload)
        );
    }

    @Test
    void duringAnActiveTransactionThePushWaitsForCommitRatherThanSendingImmediately() {
        NotificationPublisher publisher = new NotificationPublisher(messagingTemplate);
        NotificationResponse payload = payload();

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publish(RECIPIENT_ID, payload);

            // Nothing sent yet, so a rollback after this point can never leave a notification behind.
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());

            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

            verify(messagingTemplate).convertAndSendToUser(
                eq(RECIPIENT_ID.toString()), eq(WebSocketConfig.USER_NOTIFICATION_QUEUE), eq(payload)
            );
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aTransactionThatNeverCommitsNeverSendsThePush() {
        // On rollback Spring never invokes afterCommit, so no notification goes out for a failed transaction.
        NotificationPublisher publisher = new NotificationPublisher(messagingTemplate);

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publish(RECIPIENT_ID, payload());
            // ... transaction rolls back here; no afterCommit call is ever made ...
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void aDeliveryFailureIsSwallowedNotThrown() {
        NotificationPublisher publisher = new NotificationPublisher(messagingTemplate);
        doThrow(new MessagingException("socket closed"))
            .when(messagingTemplate).convertAndSendToUser(any(), any(), any());

        // Must not throw: the Notification row is already written, so a failed push must not look like a failed write.
        assertThatCode(() -> publisher.publish(RECIPIENT_ID, payload())).doesNotThrowAnyException();
    }
}
