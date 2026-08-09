package com.obdeadsoup.devpilot.outbox.domain;

import com.obdeadsoup.devpilot.outbox.config.OutboxProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** 按失败次数计算确定性指数退避；达到上限后由状态服务转入 DEAD，避免无限热循环。 */
@Component
public class OutboxRetryPolicy {

    private final OutboxProperties properties;

    public OutboxRetryPolicy(OutboxProperties properties) {
        this.properties = properties;
    }

    public boolean exhausted(int failureCount) {
        return failureCount > properties.maxRetries();
    }

    public LocalDateTime nextRetryAt(LocalDateTime now, int failureCount) {
        Duration delay = properties.initialBackoff();
        for (int i = 1; i < failureCount && delay.compareTo(properties.maxBackoff()) < 0; i++) {
            if (delay.compareTo(properties.maxBackoff().dividedBy(2)) > 0) {
                delay = properties.maxBackoff();
            } else {
                delay = delay.multipliedBy(2);
            }
        }
        if (delay.compareTo(properties.maxBackoff()) > 0) {
            delay = properties.maxBackoff();
        }
        return now.plus(delay);
    }
}
