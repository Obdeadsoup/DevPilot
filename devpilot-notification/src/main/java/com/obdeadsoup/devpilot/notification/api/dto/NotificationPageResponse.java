package com.obdeadsoup.devpilot.notification.api.dto;
import java.util.List;
public record NotificationPageResponse(int page,int size,long total,List<NotificationResponse> items){public NotificationPageResponse{items=List.copyOf(items);}}
