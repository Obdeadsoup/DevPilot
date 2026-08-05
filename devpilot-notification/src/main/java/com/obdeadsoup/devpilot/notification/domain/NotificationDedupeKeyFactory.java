package com.obdeadsoup.devpilot.notification.domain;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 由稳定业务事实生成受控 key；recipient 由唯一索引首列区分，绝不使用本轮扫描时间。 */
@Component
public class NotificationDedupeKeyFactory {
    private long epoch(LocalDateTime value){ if(value==null) throw new IllegalArgumentException("time is required"); return value.toInstant(ZoneOffset.UTC).toEpochMilli(); }
    public String taskDueSoon(long id,LocalDateTime due){return "task:"+id+":due-soon:"+epoch(due);}
    public String taskOverdue(long id,LocalDateTime due){return "task:"+id+":overdue:"+epoch(due)+":initial";}
    public String taskOverdueEscalated(long id,LocalDateTime due){return "task:"+id+":overdue:"+epoch(due)+":escalation:24h";}
    public String taskReviewTimeout(long id,LocalDateTime start){return "task:"+id+":review-timeout:"+epoch(start);}
    /** Head SHA 变化代表新代码，必须允许新一轮 Review 提醒。 */
    public String pullRequestReviewTimeout(long id,String sha,LocalDateTime start){
        if(sha==null||!sha.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("invalid head sha");
        return "pr:"+id+":review-timeout:"+sha+":"+epoch(start);
    }
}
