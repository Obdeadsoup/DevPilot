package com.obdeadsoup.devpilot.notification.application;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;
import org.springframework.stereotype.Component;
import java.util.*;
/** 只从本地 userId 与 ACTIVE 成员关系解析接收人；不使用 GitHub login。 */
@Component
public class NotificationRecipientResolver {
 private final ProjectNotificationRecipientQuery managers;
 public NotificationRecipientResolver(ProjectNotificationRecipientQuery m){managers=m;}
 public long assigneeOrReporter(Long assignee,long reporter){return assignee==null?reporter:assignee;}
 public Set<Long> assigneeAndManagers(Long assignee,long w,long p){Set<Long> r=new LinkedHashSet<>(managers.findManagerUserIds(w,p));if(assignee!=null)r.add(assignee);return r;}
 public Set<Long> managers(long w,long p){return managers.findManagerUserIds(w,p);}
}
