package com.obdeadsoup.devpilot.notification.api;
import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.notification.api.dto.*;
import com.obdeadsoup.devpilot.notification.application.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/v1/notifications")
public class NotificationController {
 private final NotificationQueryService query;private final NotificationApplicationService app;
 public NotificationController(NotificationQueryService q,NotificationApplicationService a){query=q;app=a;}
 @GetMapping public ApiResponse<NotificationPageResponse> list(@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.success(query.list(status,page,size));}
 @GetMapping("/unread-count") public ApiResponse<Map<String,Long>> unread(){return ApiResponse.success(Map.of("count",query.unreadCount()));}
 @PostMapping("/{id}/read") public ApiResponse<Void> read(@PathVariable long id,@Valid @RequestBody MarkNotificationReadRequest r){app.markRead(id,r.expectedVersion());return ApiResponse.success(null);}
 @PostMapping("/read-all") public ApiResponse<Map<String,Integer>> readAll(){return ApiResponse.success(Map.of("updated",app.markAllRead()));}
}
