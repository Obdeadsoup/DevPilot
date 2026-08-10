package com.obdeadsoup.devpilot.notification.persistence.mapper;
import com.obdeadsoup.devpilot.notification.application.CreateNotificationCommand;
import com.obdeadsoup.devpilot.notification.persistence.entity.NotificationEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.*;

/** Notification Inbox/查询 Mapper；所有用户写查询都把 recipient 放进 SQL 条件。 */
@Mapper
public interface NotificationMapper {
 String C="id,recipient_user_id AS recipientUserId,workspace_id AS workspaceId,project_id AS projectId,notification_type AS notificationType,title,content,target_type AS targetType,target_id AS targetId,target_path AS targetPath,source_type AS sourceType,source_id AS sourceId,dedupe_key AS dedupeKey,status,read_at AS readAt,occurred_at AS occurredAt,created_at AS createdAt,updated_at AS updatedAt,version";
 @Insert("INSERT INTO dp_notification(recipient_user_id,workspace_id,project_id,notification_type,title,content,target_type,target_id,target_path,source_type,source_id,dedupe_key,occurred_at) VALUES(#{recipientUserId},#{workspaceId},#{projectId},#{type},#{title},#{content},#{targetType},#{targetId},#{targetPath},#{sourceType},#{sourceId},#{dedupeKey},#{occurredAt})")
 int insert(CreateNotificationCommand c);
 @Select("SELECT "+C+" FROM dp_notification WHERE id=#{id} AND recipient_user_id=#{userId}") Optional<NotificationEntity> findForRecipient(@Param("id")long id,@Param("userId")long userId);
 @Select("SELECT "+C+" FROM dp_notification WHERE recipient_user_id=#{userId} AND dedupe_key=#{dedupeKey}") Optional<NotificationEntity> findByRecipientAndDedupe(@Param("userId")long userId,@Param("dedupeKey")String dedupeKey);
 @Select("<script>SELECT "+C+" FROM dp_notification WHERE recipient_user_id=#{userId}<if test='status != null'> AND status=#{status}</if> ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>") List<NotificationEntity> findPage(@Param("userId")long userId,@Param("status")String status,@Param("offset")long offset,@Param("size")int size);
 @Select("<script>SELECT COUNT(*) FROM dp_notification WHERE recipient_user_id=#{userId}<if test='status != null'> AND status=#{status}</if></script>") long count(@Param("userId")long userId,@Param("status")String status);
 @Select("SELECT COUNT(*) FROM dp_notification WHERE recipient_user_id=#{userId} AND status='UNREAD'") long unreadCount(@Param("userId")long userId);
 @Update("UPDATE dp_notification SET status='READ',read_at=#{now},version=version+1 WHERE id=#{id} AND recipient_user_id=#{userId} AND status='UNREAD' AND version=#{version}") int markRead(@Param("id")long id,@Param("userId")long userId,@Param("version")long version,@Param("now")LocalDateTime now);
 @Update("UPDATE dp_notification SET status='READ',read_at=#{now},version=version+1 WHERE recipient_user_id=#{userId} AND status='UNREAD'") int markAllRead(@Param("userId")long userId,@Param("now")LocalDateTime now);
}
