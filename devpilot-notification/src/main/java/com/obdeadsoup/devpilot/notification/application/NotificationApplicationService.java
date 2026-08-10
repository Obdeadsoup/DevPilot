package com.obdeadsoup.devpilot.notification.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.notification.error.NotificationErrorCode;
import com.obdeadsoup.devpilot.notification.event.NotificationCommittedEvent;
import com.obdeadsoup.devpilot.notification.persistence.mapper.NotificationMapper;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notification 的可靠写入口：Reminder 是发现规则，Notification 是已持久化事实，Channel 只是未来传输方式。
 * 本服务先短事务落库；SSE 即使未来断线，也不能让通知事实丢失。
 */
@Service
public class NotificationApplicationService {

    private final NotificationMapper mapper;
    private final ProjectNotificationRecipientQuery recipients;
    private final CurrentUserProvider users;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ApplicationEventPublisher events;

    public NotificationApplicationService(
            NotificationMapper mapper,
            ProjectNotificationRecipientQuery recipients,
            CurrentUserProvider users,
            Clock clock,
            MeterRegistry metrics,
            ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.recipients = recipients;
        this.users = users;
        this.clock = clock;
        this.metrics = metrics;
        this.events = events;
    }

    /** 每条通知独立短事务；多实例同时 INSERT 由 recipient + dedupeKey 唯一索引仲裁，冲突是正常去重。 */
    @Transactional
    public NotificationCreateResult createIfAbsent(CreateNotificationCommand command) {
        validate(command);
        try {
            mapper.insert(command);
            long notificationId = mapper.findByRecipientAndDedupe(
                            command.recipientUserId(), command.dedupeKey())
                    .orElseThrow(() -> new IllegalStateException("Created notification is not visible"))
                    .id();
            events.publishEvent(new NotificationCommittedEvent(
                    notificationId, command.recipientUserId(), command.occurredAt()));
            metrics.counter("notification.created", "result", "created").increment();
            return NotificationCreateResult.CREATED;
        } catch (DuplicateKeyException exception) {
            metrics.counter("notification.created", "result", "deduplicated").increment();
            return NotificationCreateResult.ALREADY_EXISTS;
        }
    }

    /** 只允许当前接收人以期望 version 标记未读通知；已经 READ 时重复调用仍视为成功。 */
    @Transactional
    public void markRead(long id, long version) {
        long currentUserId = users.requireUserId();
        if (mapper.markRead(id, currentUserId, version, LocalDateTime.now(clock)) == 1) {
            return;
        }

        var notification = mapper.findForRecipient(id, currentUserId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOT_FOUND));
        if (!"READ".equals(notification.status())) {
            throw new BusinessException(NotificationErrorCode.VERSION_CONFLICT);
        }
    }

    /** 只批量更新当前用户的 UNREAD 通知，不接受客户端传入 recipient。 */
    @Transactional
    public int markAllRead() {
        return mapper.markAllRead(users.requireUserId(), LocalDateTime.now(clock));
    }

    private void validate(CreateNotificationCommand command) {
        if (command == null
                || command.title() == null
                || command.title().isBlank()
                || command.title().length() > 255
                || command.content() == null
                || command.content().isBlank()
                || command.content().length() > 2000
                || command.dedupeKey() == null
                || command.dedupeKey().length() > 500
                || command.occurredAt() == null
                || command.targetPath() == null
                || !command.targetPath().startsWith("/api/v1/")
                || command.targetPath().contains("://")) {
            throw new BusinessException(NotificationErrorCode.INVALID);
        }
        if (!recipients.isActiveRecipientInScope(
                command.recipientUserId(), command.workspaceId(), command.projectId())) {
            throw new BusinessException(NotificationErrorCode.SCOPE_FORBIDDEN);
        }
    }
}
