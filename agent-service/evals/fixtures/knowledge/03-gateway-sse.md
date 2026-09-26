# [EVAL-V2] 03-gateway-sse

## SSE Timeout Policy

Gateway 为 AgentRun SSE 显式取消普通 REST 响应超时；普通 REST 仍保持短超时。浏览器终态后关闭连接，异常断开先查询 Java 权威 Run 状态。

## Replay Gap

Last-Event-ID 支持有界重放。Core 重启后若旧终态缓存缺失，SSE 发送 replay-gap 并关闭，客户端仍可通过 History 获取权威终态。
