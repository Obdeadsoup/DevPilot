package com.obdeadsoup.devpilot.notification.config;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

/** 提醒扫描的有界配置；Duration 全部必须为正，批次限制避免一次扫描拖垮数据库。 */
@Validated @ConfigurationProperties("devpilot.notification.reminder")
public record NotificationReminderProperties(boolean enabled,@NotNull @PositiveDuration Duration scanInterval,
 @Min(1) @Max(1000) int batchSize,@NotNull @PositiveDuration Duration taskDueSoonWindow,
 @NotNull @PositiveDuration Duration taskOverdueEscalationDelay,@NotNull @PositiveDuration Duration taskReviewTimeout,
 @NotNull @PositiveDuration Duration pullRequestReviewTimeout) { }
