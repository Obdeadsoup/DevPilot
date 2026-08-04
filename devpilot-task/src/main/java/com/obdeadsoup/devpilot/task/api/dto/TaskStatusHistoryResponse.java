package com.obdeadsoup.devpilot.task.api.dto;
import com.obdeadsoup.devpilot.task.domain.TaskAction;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskStatusHistoryEntity;
import java.time.LocalDateTime;
public record TaskStatusHistoryResponse(long id,TaskStatus fromStatus,TaskStatus toStatus,TaskAction action,long actorUserId,
                                        String reason,long taskVersion,LocalDateTime occurredAt){
    public static TaskStatusHistoryResponse from(TaskStatusHistoryEntity h){return new TaskStatusHistoryResponse(h.id(),h.fromStatus()==null?null:TaskStatus.valueOf(h.fromStatus()),TaskStatus.valueOf(h.toStatus()),TaskAction.valueOf(h.action()),h.actorUserId(),h.reason(),h.taskVersion(),h.occurredAt());}
}
