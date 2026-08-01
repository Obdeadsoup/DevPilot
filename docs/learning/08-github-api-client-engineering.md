# 第 8 节：GitHub REST API Client 工程化

## 1. 外部 API Client 分层

本节把“调用 GitHub”拆成四层：`GitHubRepositoryBindingService` 负责本地 RBAC、生命周期与事务；
`GitHubRepositoryMetadataClient` 负责 Repository Endpoint 的路径和响应映射；`GitHubApiHttpExecutor`
负责所有 HTTP 共性；`RestClient` 只负责发出请求。业务 Client 不直接读取环境变量，也不绕过 Executor
自行处理状态码、Retry 或日志。

```text
Controller
→ GitHubRepositoryBindingService
→ GitHubRepositoryMetadataClient
→ GitHubApiHttpExecutor
→ GitHubAccessTokenProvider + ConcurrencyLimiter + RestClient
→ GitHub API
```

## 2. RestClient 配置与固定边界

`GitHubRestClientConfiguration` 显式使用 JDK `ClientHttpRequestFactory`，生产 Base URL 固定为
`https://api.github.com`，默认发送 `Accept: application/vnd.github+json`、项目已验证的
`X-GitHub-Api-Version: 2022-11-28` 和 `User-Agent: DevPilot`。Authorization 不能成为全局 Header；
Executor 在获得本次请求的 Token 后动态添加。只有名称含 `test` 的 Profile 可以使用 loopback Mock Host，
测试配置默认指向不可用的 `127.0.0.1:1`，避免误访问真实 GitHub。

## 3. connect timeout 与 read timeout

connect timeout 限制建立 TCP/TLS 连接的时间，默认 2 秒；read timeout 限制连接建立后等待响应数据的时间，
默认 5 秒。二者都转换为不携带底层地址和 Token 的 `NETWORK_ERROR`，读取请求可在最大次数内 Retry。
Timeout 不能解决所有资源占用问题，所以还需要每 Credential Semaphore 和总尝试次数上限。

## 4. Error Decoder

`GitHubApiErrorDecoder` 是 HTTP 失败到稳定失败类型的唯一入口：400/422 为 `VALIDATION`，401 为
`AUTHENTICATION`，普通 403 为 `ACCESS_DENIED`，404 为 `NOT_FOUND`，409 为 `CONFLICT`，
500/502/503/504 为 `TRANSIENT_SERVER_ERROR`，429 及有明确证据的 403 为 `RATE_LIMITED`。
网络异常由 Executor 转成 `NETWORK_ERROR`，关键 JSON 字段缺失或响应不可解析转成
`MALFORMED_RESPONSE`。异常仅保存安全消息、状态、retryAt、requestId 与 Rate Limit 快照，不保存完整正文。

## 5. 可重试与不可重试错误

可自动 Retry 的失败只有 `NETWORK_ERROR`、临时 5xx 和明确可重试的 `RATE_LIMITED`。400、401、
普通 403、404、409、422、坏 JSON、凭据缺失和并发许可超时都不会自动 Retry。特别地，403 只有在
`Retry-After`、`X-RateLimit-Remaining=0` 或安全解析出的 Primary/Secondary Rate Limit 消息支持时才是限流；
GitHub 普遍返回的 `X-RateLimit-Reset` 单独存在不足以把所有 403 误判为限流。

## 6. 为什么只自动重试读取请求

GET 和 HEAD 语义上是读取，可以在有限次数内重复发送。POST、PUT、PATCH、DELETE 可能已经在远端生效，
仅因客户端没有收到响应就重发会制造重复写入。本节 Executor 只公开 GET/HEAD；Retry Policy 即使将来收到
写方法也明确停止。未来写 API 必须使用 GitHub 幂等条件、状态核对或业务补偿，而不是套用读取 Retry。

## 7. Primary 与 Secondary Rate Limit

Primary Rate Limit 主要由 `X-RateLimit-Limit/Remaining/Used/Reset/Resource` 描述；Secondary
Rate Limit 是 GitHub 的滥用与并发保护，可能在 Remaining 尚未归零时返回 403/429，并通过
`Retry-After` 或安全错误类型提示。`GitHubRateLimitSnapshot` 同时保留两类判断所需 Header 和
`X-GitHub-Request-Id`，但不会把 Repository、用户或凭据加入指标标签。

## 8. Retry-After、Reset 与 Backoff

等待优先级是 `Retry-After`、`X-RateLimit-Reset`、指数 Backoff + jitter。前两者表达服务器给出的时间，
最后一种只是在没有服务端时间时减少同步重试碰撞。`maxReadAttempts` 默认 3、范围 1～5；Backoff 从
200ms 开始并封顶 2s。注入的 Clock、jitter source 和 Sleeper 让测试无需 `Thread.sleep`。

## 9. 为什么长时间限流不阻塞请求线程

若限流等待超过 `maxSynchronousRateLimitWait`（默认 3s），Retry Policy 直接返回停止决策，并抛出带
`retryAt` 的 `RATE_LIMITED`。同步 Web 请求线程不应睡到 GitHub 的 Reset 时间。未来后台 Issue/PR 同步
可以把该时间转换为自己的 `RETRY_WAIT`；本节没有实现该同步状态机。

## 10. Link Header 分页

GitHub 使用 RFC 风格 `Link` Header 给出 `next/prev/first/last`。解析器扫描尖括号范围，不用简单
`split(",")` 破坏 URI；返回 `GitHubPageCursor`，下一页由 Executor 读取，业务层不手工 `page++`。
Link 属于外部输入，访问前必须验证 scheme、host、port、userInfo 与配置端点同源，否则攻击者可能借
Bearer Token 发起 SSRF。生产只允许 GitHub Host，测试只允许已配置 loopback Host。

## 11. ETag、Last-Modified 与 304

V7 在 Binding 中保存 `metadata_etag` 与 `metadata_last_modified`。刷新时构造
`GitHubConditionalRequest`，发送 `If-None-Match`/`If-Modified-Since`。200 路径重新校验稳定
Repository ID，更新权威字段、校验器、`last_verified_at` 并 `version + 1`；304 是成功响应，不进入
Error Decoder，不覆盖任何权威字段或原校验器，只更新 `last_verified_at` 并 `version + 1`。
两条 SQL 都带 Workspace/Project、状态、deleted 与 expectedVersion 条件。ETag 只用于服务器端缓存验证，
普通 API Response 不暴露它。

## 12. API Version

固定 `X-GitHub-Api-Version` 可以避免 GitHub 默认版本变化静默改变 JSON 或错误语义。本节沿用
`2022-11-28`，配置可由部署者显式修改，但代码没有“自动升级最新版本”的行为；版本升级应单独验证并发布。

## 13. GitHub Request ID

`X-GitHub-Request-Id` 被解析到响应、Rate Limit 快照和安全异常中，用于与 GitHub Support 或服务端日志关联。
它适合日志字段，不适合 Micrometer 标签，因为每次请求不同会造成高基数。

## 14. 安全日志与低基数指标

日志字段包括 operation、endpoint template、status、durationMs、attempt、requestId、Remaining、Reset、
failureType 和 Retry 决策；不包括 Token、Secret、Authorization、完整 Payload、具体仓库路径或完整错误正文。
Micrometer 提供 `github.api.requests/failures/retries/rate_limited/duration`，标签仅使用 operation、method、
status 和 failureType 等有限集合，不使用 fullName、userId、requestId 或 credential reference。

## 15. Credential 并发限制

`GitHubCredentialConcurrencyLimiter` 以 credential reference 的 SHA-256 为 Map Key，每个 Credential 默认
最多两个并发请求，限时获取失败分类为 `CONCURRENCY_LIMITED`。Permit 使用 try-with-resources 释放，异常路径
也不会泄漏。不同 Credential 使用不同 Semaphore，不会用一个全局锁串行全部 Workspace。它只在单 JVM
有效；多实例总体并发以后需要更高层调度或共享协调。

## 16. PAT 与 GitHub App Token Provider 抽象

`GitHubAccessTokenProvider` 隔离 Token 来源，返回的 `GitHubAccessToken` 允许过期时间为空。当前实现仅接受
`DEVPILOT_GITHUB_API_TOKEN_[A-Z0-9_]+` 环境变量引用，适合 Fine-grained PAT；数据库保存 reference，业务层
不读取 Environment，原始 Token 只在 HTTP 构建时短暂出现且 `toString` 已脱敏。未来可增加 Installation
Token Provider，但本轮没有实现 GitHub App JWT 或 Installation Token 请求。

## 17. 当前边界与下一步

本节完成的是 Repository Metadata 所需的工程化读取底座：固定端点、Timeout、错误分类、有限 Retry、
Rate Limit、分页游标、Conditional GET、凭据并发、日志和指标。尚未实现 Issue/PR/Review 同步、分页业务循环、
GitHub App Authentication、Token 刷新/失效状态机、跨实例并发协调和后台 API 同步的 RETRY_WAIT/DEAD。
现有 Webhook HMAC、Delivery 重试恢复与 Secret 分离没有改变。
