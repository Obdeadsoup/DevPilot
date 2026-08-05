package com.obdeadsoup.devpilot.notification.application;

import com.obdeadsoup.devpilot.github.application.port.PullRequestReviewState;
import com.obdeadsoup.devpilot.github.application.port.PullRequestReviewStateReader;
import com.obdeadsoup.devpilot.notification.config.NotificationReminderProperties;
import com.obdeadsoup.devpilot.notification.domain.NotificationDedupeKeyFactory;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;
import com.obdeadsoup.devpilot.task.application.port.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

class NotificationReminderScanServiceTest {
 @Test void excludesTerminalCandidatesAtPortAndUsesAllRules(){var tasks=mock(TaskReminderCandidateReader.class);var prs=mock(PullRequestReviewStateReader.class);var app=mock(NotificationApplicationService.class);var managers=mock(ProjectNotificationRecipientQuery.class);empty(tasks);service(tasks,prs,app,managers).scan();verify(tasks).findDueSoon(any(),any(),eq(100));verify(tasks,times(2)).findReviewTimeout(any(),eq(100));verifyNoInteractions(app);}
 @Test void currentHeadApprovalSuppressesButOldHeadDoesNot(){
  var tasks=mock(TaskReminderCandidateReader.class);var prs=mock(PullRequestReviewStateReader.class);var app=mock(NotificationApplicationService.class);var managers=mock(ProjectNotificationRecipientQuery.class);
  var c=new TaskReminderCandidate(1,2,3,"P-1","Review","IN_REVIEW",7,8L,null,LocalDateTime.of(2026,8,1,1,0),4);
  when(tasks.findDueSoon(any(),any(),anyInt())).thenReturn(List.of());when(tasks.findOverdue(any(),anyInt())).thenReturn(List.of());when(tasks.findOverdueForEscalation(any(),anyInt())).thenReturn(List.of());when(tasks.findReviewTimeout(any(),anyInt())).thenReturn(List.of(c));when(managers.findManagerUserIds(2,3)).thenReturn(Set.of());
  when(prs.findForTask(2,3,1)).thenReturn(Optional.of(state("a",true)));service(tasks,prs,app,managers).scan();verify(app,times(1)).createIfAbsent(any());
  reset(app);when(prs.findForTask(2,3,1)).thenReturn(Optional.of(state("b",false)));service(tasks,prs,app,managers).scan();verify(app,times(2)).createIfAbsent(any());
 }
 private PullRequestReviewState state(String sha,boolean approved){return new PullRequestReviewState(9,99,5,"OPEN",false,sha.repeat(40),"https://github.com/x/y/pull/5",approved,"APPROVED",2,3);}
 private void empty(TaskReminderCandidateReader t){when(t.findDueSoon(any(),any(),anyInt())).thenReturn(List.of());when(t.findOverdue(any(),anyInt())).thenReturn(List.of());when(t.findOverdueForEscalation(any(),anyInt())).thenReturn(List.of());when(t.findReviewTimeout(any(),anyInt())).thenReturn(List.of());}
 private NotificationReminderScanService service(TaskReminderCandidateReader t,PullRequestReviewStateReader p,NotificationApplicationService a,ProjectNotificationRecipientQuery m){var x=new NotificationReminderProperties(true,Duration.ofMinutes(1),100,Duration.ofHours(24),Duration.ofHours(24),Duration.ofHours(24),Duration.ofHours(24));return new NotificationReminderScanService(t,p,a,new NotificationDedupeKeyFactory(),new NotificationRecipientResolver(m),x,Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"),ZoneOffset.UTC));}
}
