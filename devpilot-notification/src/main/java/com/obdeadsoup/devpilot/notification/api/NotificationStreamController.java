package com.obdeadsoup.devpilot.notification.api;

import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.notification.application.NotificationQueryService;
import com.obdeadsoup.devpilot.notification.sse.NotificationConnectedSseData;
import com.obdeadsoup.devpilot.notification.sse.NotificationSseRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 只用建立连接时的 Principal 解析 userId；后续异步 send 不读取 ThreadLocal SecurityContext。 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationStreamController {

    private final CurrentUserProvider currentUserProvider;
    private final NotificationQueryService queries;
    private final NotificationSseRegistry registry;

    public NotificationStreamController(
            CurrentUserProvider currentUserProvider,
            NotificationQueryService queries,
            NotificationSseRegistry registry) {
        this.currentUserProvider = currentUserProvider;
        this.queries = queries;
        this.registry = registry;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        long userId = currentUserProvider.requireUserId();
        SseEmitter emitter = registry.register(userId);
        registry.sendConnected(
                userId, emitter, new NotificationConnectedSseData(true, queries.unreadCountForRecipient(userId)));
        return emitter;
    }
}
