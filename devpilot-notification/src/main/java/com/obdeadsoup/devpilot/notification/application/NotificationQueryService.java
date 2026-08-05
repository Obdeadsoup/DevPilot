package com.obdeadsoup.devpilot.notification.application;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.notification.api.dto.*;
import com.obdeadsoup.devpilot.notification.persistence.entity.NotificationEntity;
import com.obdeadsoup.devpilot.notification.persistence.mapper.NotificationMapper;
import org.springframework.stereotype.Service;
import java.util.Locale;

/** 所有查询只使用 Principal 对应 userId；返回安全投影，不暴露内部 dedupeKey。 */
@Service
public class NotificationQueryService {
 private final NotificationMapper mapper;private final CurrentUserProvider users;
 public NotificationQueryService(NotificationMapper m,CurrentUserProvider u){mapper=m;users=u;}
 public NotificationPageResponse list(String status,int page,int size){int p=Math.max(1,page),s=Math.min(100,Math.max(1,size));String st=normalize(status);long uid=users.requireUserId();return new NotificationPageResponse(p,s,mapper.count(uid,st),mapper.findPage(uid,st,(long)(p-1)*s,s).stream().map(this::map).toList());}
 public long unreadCount(){return mapper.unreadCount(users.requireUserId());}
 private String normalize(String s){if(s==null||s.isBlank())return null;try{return com.obdeadsoup.devpilot.notification.domain.NotificationStatus.valueOf(s.toUpperCase(Locale.ROOT)).name();}catch(Exception e){throw new com.obdeadsoup.devpilot.framework.error.BusinessException(com.obdeadsoup.devpilot.notification.error.NotificationErrorCode.INVALID);}}
 private NotificationResponse map(NotificationEntity n){return new NotificationResponse(n.id(),n.workspaceId(),n.projectId(),n.notificationType(),n.title(),n.content(),n.targetType(),n.targetId(),n.targetPath(),n.sourceType(),n.sourceId(),n.status(),n.readAt(),n.occurredAt(),n.createdAt(),n.version());}
}
