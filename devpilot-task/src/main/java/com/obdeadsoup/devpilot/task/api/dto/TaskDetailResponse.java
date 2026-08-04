package com.obdeadsoup.devpilot.task.api.dto;
import java.util.List;
public record TaskDetailResponse(TaskResponse task,List<TaskStatusHistoryResponse> history){ public TaskDetailResponse{history=List.copyOf(history);} }
