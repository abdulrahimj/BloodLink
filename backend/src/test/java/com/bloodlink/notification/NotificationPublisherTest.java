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
 * The two things that make this class more than a one-line call to
 * SimpMessagingTemplate: a push made mid-transaction waits for that transaction to
 * actually commit before going out, and a delivery failure is swallowed rather
 * than allowed to fail the write that caused it.
 *
 * Both are timing- and exception-shaped behaviour that a service-layer test
 * calling the real NotificationService could easily miss — this exercises them
 * directly against NotificationPublisher itself.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

    private static final UUID RECIPIENT_ID = UUID.randomUUID();

    @Mock private SimpMessagingTemplate messagingTemplate;

    @AfterEach
    void clearAnyOpenSynchronization() {
        // Defensive: a test that fails before reaching its own cleanup line must
        // not leave transaction synchronization active for whichever test runs
        // next on the same thread.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static NotificationResponse payload() {
        return new NotificationResponse(
            UUID.randomUUID(), "Test message", NotificationType.LOW_STOCK_ALERT, false, Instant.now()
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

            // Nothing sent yet — the whole point is that a rollback after this line
            // must still be possible without a notification having already gone out
            // for something that never actually happened.
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
        // The rollback case: afterCommit is simply never invoked by Spring in this
        // scenario, which is exactly what protects a notification from going out
        // for a fulfilment (say) that failed after the alert was built.
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

        // Must not throw: the durable Notification row is already written by the
        // time this runs, and a push that fails to go out must never look, to the
        // caller, like the write itself failed.
        assertThatCode(() -> publisher.publish(RECIPIENT_ID, payload())).doesNotThrowAnyException();
    }
}
