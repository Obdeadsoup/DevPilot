package com.obdeadsoup.devpilot.notification.sse;

import com.obdeadsoup.devpilot.notification.application.NotificationQueryService;
import com.obdeadsoup.devpilot.notification.event.NotificationCommittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Notification 真正提交后向当前实例在线连接尽力发送最小 ID Envelope。SSE 失败不回滚 Notification，
 * Outbox PROCESSED 也只表示持久化副作用完成，不表示浏览器已经收到。
 */
@Component
public class NotificationSsePublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationSsePublisher.class);

    private final NotificationSseRegistry registry;
    private final NotificationQueryService queries;

    public NotificationSsePublisher(NotificationSseRegistry registry, NotificationQueryService queries) {
        this.registry = registry;
        this.queries = queries;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(NotificationCommittedEvent event) {
        try {
            long unreadCount = queries.unreadCountForRecipient(event.recipientUserId());
            registry.sendNotification(
                    event.recipientUserId(),
                    event.notificationId(),
                    new NotificationCreatedSseData(
                            event.notificationId(), unreadCount, event.occurredAt()));
        } catch (RuntimeException exception) {
            // 事务已经提交；SSE 是可丢失 Channel，异常不得反向污染可靠的 Notification/Outbox 结果。
            log.warn("Notification SSE push failed notificationId={} failureType={}",
                    event.notificationId(), exception.getClass().getSimpleName());
        }
    }
}
