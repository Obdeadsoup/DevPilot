package com.obdeadsoup.devpilot.notification.application;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;
/** 薄 fixedDelay 入口：上一轮完成后再计时，避免慢扫描与下一轮在同实例重叠。 */
@Component @ConditionalOnProperty(prefix="devpilot.notification.reminder",name="enabled",havingValue="true")
public class NotificationReminderScheduler {
 private final NotificationReminderScanService scans;public NotificationReminderScheduler(NotificationReminderScanService s){scans=s;}
 @Scheduled(fixedDelayString="${devpilot.notification.reminder.scan-interval:1m}") public void scan(){scans.scan();}
}
