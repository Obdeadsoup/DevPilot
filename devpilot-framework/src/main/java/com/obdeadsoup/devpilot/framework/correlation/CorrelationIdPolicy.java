package com.obdeadsoup.devpilot.framework.correlation;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 校验外部 Correlation ID 的安全格式，并为缺失或非法请求生成服务端标识。 */
@Component
public class CorrelationIdPolicy {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    public String resolve(String candidate) {
        return isValid(candidate) ? candidate : generate();
    }

    /** 判断外部请求标识是否可安全进入响应头、下游请求和日志上下文。 */
    public boolean isValid(String candidate) {
        return candidate != null && SAFE_VALUE.matcher(candidate).matches();
    }

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
