package com.obdeadsoup.devpilot.notification.domain;
import org.junit.jupiter.api.Test;import java.time.LocalDateTime;import static org.assertj.core.api.Assertions.*;
class NotificationDedupeKeyFactoryTest {
 private final NotificationDedupeKeyFactory f=new NotificationDedupeKeyFactory();private final LocalDateTime t=LocalDateTime.of(2026,8,4,12,0);
 @Test void buildsEveryStableKey(){assertThat(f.taskDueSoon(1,t)).isEqualTo(f.taskDueSoon(1,t));assertThat(f.taskOverdue(1,t)).endsWith(":initial");assertThat(f.taskOverdueEscalated(1,t)).endsWith(":escalation:24h");assertThat(f.taskReviewTimeout(1,t)).contains(":review-timeout:");assertThat(f.pullRequestReviewTimeout(9,"a".repeat(40),t)).startsWith("pr:9:review-timeout:");}
 @Test void dueAtChangeCreatesNewKey(){assertThat(f.taskDueSoon(1,t)).isNotEqualTo(f.taskDueSoon(1,t.plusMinutes(1)));}
 @Test void headChangeCreatesNewKey(){assertThat(f.pullRequestReviewTimeout(9,"a".repeat(40),t)).isNotEqualTo(f.pullRequestReviewTimeout(9,"b".repeat(40),t));}
}
