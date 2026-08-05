package com.obdeadsoup.devpilot.notification.error;
import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;
public enum NotificationErrorCode implements ErrorCode {
    NOT_FOUND("NOTIFICATION_0404","Notification not found",HttpStatus.NOT_FOUND),
    INVALID("NOTIFICATION_0400","Invalid notification",HttpStatus.BAD_REQUEST),
    VERSION_CONFLICT("NOTIFICATION_0501","Notification version conflict",HttpStatus.CONFLICT),
    SCOPE_FORBIDDEN("NOTIFICATION_0403","Notification scope forbidden",HttpStatus.FORBIDDEN);
    private final String code,message; private final HttpStatus status;
    NotificationErrorCode(String c,String m,HttpStatus s){code=c;message=m;status=s;}
    public String code(){return code;} public String message(){return message;} public HttpStatus status(){return status;}
}
