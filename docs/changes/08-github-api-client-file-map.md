# 第 8 节文件地图：GitHub REST API Client 工程化

## 1. 本节目标

在第 7 节 Repository Binding 之上建立单一、安全、可测试的 GitHub 读取执行链，覆盖固定 Host、动态凭据、
Timeout、错误分类、有限 Retry、Rate Limit、Link 分页、Conditional GET、每 Credential 并发和低基数观测；
把 Metadata Client 收敛为 Endpoint 映射器。本节不实现 Issue/PR 同步或 GitHub App Authentication。

## 2. 完整变更目录树

```text
[MOD] .gitignore
[NEW] AGENTS.md
[MOD] README.md
[MOD] docs/architecture.md
[MOD] docs/database-design.md
[MOD] docs/capability-coverage-and-roadmap.md
[NEW] docs/changes/08-github-api-client-file-map.md
[MOD] docs/learning/06-workspace-project-lifecycle.md
[MOD] docs/learning/07-github-repository-binding.md
[NEW] docs/learning/08-github-api-client-engineering.md
devpilot-boot/
├─ [MOD] src/main/resources/application.yml
├─ [NEW] src/main/resources/db/migration/V7__add_github_repository_metadata_validators.sql
├─ [MOD] src/test/java/.../github/GitHubRepositoryBindingIntegrationTest.java
└─ [MOD] src/test/resources/application-{test,integration-test,identity-integration-test}.yml
devpilot-github/
├─ [MOD] pom.xml
├─ src/main/java/.../github/
│  ├─ application/
│  │  ├─ [MOD] GitHubRepositoryBindingService.java
│  │  ├─ [MOD] GitHubWebhookService.java
│  │  ├─ [MOD] GitHubDelivery{RetryPolicy,FailureClassifier,RecoveryScheduler}.java
│  │  ├─ [MOD] GitHubDelivery{RecoveryService,Worker,StateService,ProcessingService}.java
│  │  ├─ client/
│  │  │  ├─ [NEW] GitHubApi{EndpointPolicy,ErrorDecoder,Exception,FailureType}.java
│  │  │  ├─ [NEW] GitHubApi{HttpExecutor,Metrics,Response,RetryPolicy,Sleeper}.java
│  │  │  ├─ [NEW] GitHub{ConditionalRequest,CredentialConcurrencyLimiter}.java
│  │  │  ├─ [NEW] GitHub{LinkHeaderParser,Page,PageCursor,RateLimitParser,RateLimitSnapshot}.java
│  │  │  ├─ [MOD] GitHubRepositoryMetadataClient.java
│  │  │  └─ [MOD] RestClientGitHubRepositoryMetadataClient.java
│  │  ├─ credential/
│  │  │  ├─ [DEL] GitHubApiCredentialResolver.java
│  │  │  ├─ [DEL] EnvironmentGitHubApiCredentialResolver.java
│  │  │  └─ [NEW] GitHub{AccessToken,AccessTokenProvider}.java
│  │  │     [NEW] EnvironmentGitHubAccessTokenProvider.java
│  │  └─ secret/[MOD] EnvironmentWebhookSecretResolver.java
│  ├─ config/[MOD] GitHubIntegration{Configuration,Properties}.java
│  │        [NEW] GitHubRestClientConfiguration.java
│  └─ persistence/
│     ├─ entity/[MOD] GitHubRepositoryEntity.java
│     └─ mapper/[MOD] GitHubRepositoryMapper.java
└─ src/test/java/.../github/
   ├─ api/[MOD] GitHubRepositoryApiContractTest.java
   ├─ application/[MOD] GitHubRepositoryBindingServiceTest.java
   │              [MOD] GitHubDeliveryRecoveryServiceTest.java
   ├─ application/client/[NEW] GitHubApiHttpExecutorTest.java
   │                    [NEW] GitHubApiErrorDecoderTest.java
   │                    [NEW] GitHubApiRetryPolicyTest.java
   │                    [NEW] GitHubLinkHeaderParserTest.java
   │                    [NEW] GitHubCredentialConcurrencyLimiterTest.java
   │                    [MOD] RestClientGitHubRepositoryMetadataClientTest.java
   ├─ application/credential/[MOD] CredentialReferenceResolverTest.java
   ├─ config/[MOD] GitHubIntegrationPropertiesTest.java
   │        [NEW] GitHubRestClientConfigurationTest.java
   └─ support/[NEW] GitHubTestProperties.java
```

花括号表示同目录下逐个独立文件，均属于本节实际 Diff；三个测试 YAML 也分别修改。

## 3. 文件职责、依赖、风险与测试

### 根目录与文档

| 文件 | 模块/层 | 职责、调用关系与关键内容 | 风险与对应测试 |
|---|---|---|---|
| `.gitignore` [MOD] | 仓库 | 让本节 AGENTS、学习文档和文件地图进入 Diff | 只调整文档白名单；由 `git status` 核对 |
| `AGENTS.md` [NEW] | 仓库规范 | 永久中文 JavaDoc 与“只解释为什么”的注释规则 | 注释漂移；评审与全文检索 |
| `README.md` [MOD] | 入口文档 | 标明工程化 Client、V7 与未实现边界 | 误述进度；与测试及代码核对 |
| `docs/architecture.md` [MOD] | 架构 | 记录 Executor、错误、限流、ETag 调用链 | 分层被绕过；架构/单元测试 |
| `docs/database-design.md` [MOD] | 数据 | 记录 V7 两个内部校验字段 | 历史 migration 被改；Flyway 集成测试 |
| `docs/capability-coverage-and-roadmap.md` [MOD] | 路线 | 将 Client 底座标为完成，业务同步保持待办 | 能力夸大；文档核对 |
| `docs/learning/06-workspace-project-lifecycle.md` [MOD] | 学习 | 同步后续章节已完成与仍待办边界 | 历史段落过期；文档核对 |
| `docs/learning/07-github-repository-binding.md` [MOD] | 学习 | 把第 7 节“下一步”同步到当前事实 | 过期描述；文档核对 |
| `docs/learning/08-github-api-client-engineering.md` [NEW] | 学习 | 解释 17 个 Client 工程主题 | 教学与真实代码不一致；按类名/配置核对 |
| 本文件 [NEW] | 变更地图 | 汇总文件、调用链、阅读顺序和 Diff | 遗漏文件；与 `git status` 对照 |

### 配置、迁移与持久化

| 文件 | 模块/层 | 职责、调用者 → 被调用者/关键方法 | 风险与对应测试 |
|---|---|---|---|
| `application.yml` [MOD] | boot/配置 | 向 Properties 提供 API 默认值 | 错误默认值；PropertiesTest/上下文测试 |
| 三个 `application-*-test.yml` [MOD] | boot/测试配置 | 固定 loopback 不可用地址，禁止真实 GitHub | 测试误联网；Boot 集成测试 |
| `V7__add_github_repository_metadata_validators.sql` [NEW] | boot/Flyway | V6 → 添加 ETag、Last-Modified | 类型/顺序错误；BindingIntegrationTest |
| `GitHubIntegrationProperties` [MOD] | github/config | 绑定和验证 Timeout、Retry、并发、Host | 非正 Duration/越界；PropertiesTest |
| `GitHubIntegrationConfiguration` [MOD] | github/config | 既有 Delivery Clock/线程池/Retry 装配，仅补语义文档 | 回归；Delivery 测试 |
| `GitHubRestClientConfiguration` [NEW] | github/config | 装配固定端点 RestClient 与统一执行链 Bean | SSRF、固定 Authorization、Timeout 缺失；Executor/上下文测试 |
| `GitHubRepositoryEntity` [MOD] | github/persistence | 内部持有 metadataEtag/metadataLastModified | 意外暴露；API Contract 测试 |
| `GitHubRepositoryMapper` [MOD] | github/persistence | insert/200/304 持久化；`markMetadataNotModified` | 304 覆盖字段、丢 version 条件；Binding 单元/集成测试 |

### HTTP、凭据与业务接入

| 文件 | 模块/层 | 职责、调用者 → 被调用者/关键方法 | 风险与对应测试 |
|---|---|---|---|
| `GitHubApiEndpointPolicy` [NEW] | github/client | Executor/Link Parser → `requireAllowed` 校验同源 | SSRF/Token 泄漏；Link/ExecutorTest |
| `GitHubApiHttpExecutor` [NEW] | github/client | Metadata Client → `get/head/getPage` → Token、Semaphore、RestClient、Decoder、Retry、Metrics | 绕过统一链、泄密、无限 Retry；ExecutorTest |
| `GitHubApiErrorDecoder` [NEW] | github/client | Executor → `decode/networkFailure/malformedResponse` | 全部 403 误 Retry、正文泄漏；ErrorDecoderTest |
| `GitHubApiRetryPolicy` [NEW] | github/client | Executor → `decide` | 写请求 Retry、长等待阻塞；RetryPolicy/ExecutorTest |
| `GitHubRateLimitParser` [NEW] | github/client | Executor/Decoder → Header 解析 | 坏 Header 导致失败、Reset 误判；DecoderTest |
| `GitHubLinkHeaderParser` [NEW] | github/client | Executor → `parse` → EndpointPolicy | 逗号拆坏 URI、恶意 Host；LinkHeaderParserTest |
| `GitHubCredentialConcurrencyLimiter` [NEW] | github/client | Executor → `acquire`/`Permit.close` | Token 做 Key、许可泄漏、全局串行；ConcurrencyLimiterTest |
| `GitHubApiMetrics` [NEW] | github/client | Executor → Counter/Timer | 高基数标签；ExecutorTest/人工检查 |
| `GitHubApiSleeper` [NEW] | github/client port | Executor 调用；生产 sleep、测试记录 Duration | 脆弱真实等待；Retry/ExecutorTest |
| `GitHubApiException`、`GitHubApiFailureType` [NEW] | github/client model | Decoder 产生，Retry/Binding 消费 | 携带敏感数据/分类漂移；Decoder/BindingTest |
| `GitHubApiResponse`、`GitHubRateLimitSnapshot` [NEW] | github/client model | Executor 返回，Metadata Client 消费 | Header 丢失；ExecutorTest |
| `GitHubPage`、`GitHubPageCursor` [NEW] | github/client model | 未来列表 Client 使用，Cursor 由 Link Parser 产生 | 业务手拼 URL；LinkHeaderParserTest |
| `GitHubConditionalRequest` [NEW] | github/client model | Binding → Metadata Client → Executor | 304 被当异常；Executor/BindingTest |
| `GitHubAccessTokenProvider`、`GitHubAccessToken` [NEW] | github/credential port | Executor → Provider | Token 生命周期/意外输出；Credential/ExecutorTest |
| `EnvironmentGitHubAccessTokenProvider` [NEW] | github/credential adapter | Provider 从受限环境变量引用解析 PAT | 任意属性读取/泄密；CredentialReferenceResolverTest |
| 两个 `GitHubApiCredentialResolver` 文件 [DEL] | github/旧 credential | 被 Token Provider 模型替代 | 遗留调用；编译与 `rg` |
| `GitHubRepositoryMetadataClient` [MOD] | github/client port | Binding 调用；新增 Conditional 和 Header Response | API 行为破坏；Metadata/BindingTest |
| `RestClientGitHubRepositoryMetadataClient` [MOD] | github/client adapter | Binding → 本类 → Executor；只映射权威 Repository 字段 | 自行处理状态/缺关键 ID；MetadataClientTest |
| `GitHubRepositoryBindingService` [MOD] | github/application | Controller → bind/reactivate/refresh → Metadata Client/Mapper | 200 身份漂移、304 覆盖、乐观锁；Binding 单元/集成测试 |
| `EnvironmentWebhookSecretResolver`、`GitHubWebhookService` [MOD] | github/既有 Webhook | 仅补符合新规范的文档；HMAC 链未改 | API Token/Secret 混用；Webhook 回归测试 |
| 八个 `GitHubDelivery*` 文件 [MOD] | github/既有 Delivery | 仅补状态、Retry、事务、并发 JavaDoc | 注释与行为漂移；retry/recovery 回归测试 |

### 测试与依赖

| 文件 | 模块/层 | 核心覆盖 | 风险 |
|---|---|---|---|
| `devpilot-github/pom.xml` [MOD] | github/build | 增加已有 Actuator 生态使用的 `micrometer-core` | 无版本/重复依赖；Maven verify |
| `GitHubApiHttpExecutorTest` [NEW] | github/unit | 默认/动态 Header、Token 脱敏、Timeout、尝试上限、5xx、304、HEAD、重定向、指标 | Mock 与真实 request factory 差异；完整构建补充 |
| `GitHubApiErrorDecoderTest` [NEW] | github/unit | 400/401/403/404/422/429/5xx、Primary/Secondary Limit、Header/requestId | 错误误分类 |
| `GitHubApiRetryPolicyTest` [NEW] | github/unit | Retry-After > Reset > Backoff+jitter、长等待、写方法停止 | 时间非确定性；固定 Clock/jitter |
| `GitHubLinkHeaderParserTest` [NEW] | github/unit | next/无 next/多 rel/逗号/恶意 Host/userInfo/test Host | SSRF 绕过 |
| `GitHubCredentialConcurrencyLimiterTest` [NEW] | github/unit | 同 Credential 限制、不同 Credential 隔离、异常释放 | 并发竞态；受控同步器 |
| `RestClientGitHubRepositoryMetadataClientTest` [MOD] | github/unit | 权威映射、Header/304 转发、缺 ID | DTO 映射漂移 |
| `GitHubRepositoryBindingServiceTest` [MOD] | github/unit | 200 validators、304 仅验证、ID 变化拒绝 | 事务 SQL 未覆盖；集成测试补充 |
| `CredentialReferenceResolverTest` [MOD] | github/unit | API Token 引用白名单与脱敏 | 环境变量范围过宽 |
| `GitHubIntegrationPropertiesTest` [MOD] | github/unit | 配置绑定、Host、Duration、Retry 范围 | 本地化校验消息；稳定字段断言 |
| `GitHubRestClientConfigurationTest` [NEW] | github/unit | Profile Host 边界、真实 loopback 请求的默认 Header、无全局 Authorization | 配置与 Executor 测试夹具漂移 |
| `GitHubTestProperties` [NEW] | github/test support | 统一构造完整 Properties | 默认值与生产漂移；测试集中复用 |
| `GitHubRepositoryApiContractTest` [MOD] | github/contract | ETag/Last-Modified 不进入前端 Response | 凭据元数据泄漏 |
| `GitHubDeliveryRecoveryServiceTest` [MOD] | github/regression | 适配完整 Properties，保持恢复语义 | Delivery 行为意外变化 |
| `GitHubRepositoryBindingIntegrationTest` [MOD] | boot/integration | V7、200 ETag、后续 Conditional 304、rename、ID mismatch | MySQL/Flyway/事务差异；Testcontainers |

## 4. 完整业务调用链

```text
GitHubRepositoryController
→ GitHubRepositoryBindingService.bind/reactivate/refresh
→ GitHubRepositoryMetadataClient.getRepository
→ RestClientGitHubRepositoryMetadataClient
→ GitHubApiHttpExecutor.get
→ GitHubAccessTokenProvider.getToken
→ GitHubCredentialConcurrencyLimiter.acquire
→ RestClient GET https://api.github.com/repos/{owner}/{repo}
→ GitHubApiResponse<RawRepositoryResponse>
→ VerifiedGitHubRepository + ETag/Last-Modified
→ GitHubRepositoryBindingService 校验稳定 repository id
→ GitHubRepositoryMapper 条件 INSERT/UPDATE
→ GitHubRepositoryResponse（不含 Token、Secret、ETag）
```

## 5. 完整错误处理调用链

```text
HTTP 非 2xx/304 或网络/JSON 失败
→ GitHubRateLimitParser 提取安全 Header
→ GitHubApiErrorDecoder 分类为 GitHubApiException
→ GitHubApiRetryPolicy 判断 method + failureType + attempt
→ 可短暂 Retry：Sleeper 后重新 GET/HEAD
→ 不可 Retry/达到上限/长限流：抛出安全异常
→ GitHubRepositoryBindingService.mapApiFailure
→ 既有稳定 GitHubRepositoryErrorCode
→ 全局异常响应
```

## 6. Rate Limit 调用链

```text
403/429 + Headers + 安全解析 message
→ Retry-After / Remaining=0 / Primary-Secondary 证据
→ RATE_LIMITED(retryAt, requestId, snapshot)
→ Retry Policy：Retry-After > Reset > Backoff+jitter
→ delay <= 3s 且有剩余尝试：同步有限 Retry
→ delay > 3s：立即返回 RATE_LIMITED，不阻塞 Web 线程
→ 日志与 github.api.rate_limited（低基数标签）
```

## 7. ETag / 304 调用链

```text
Binding(metadata_etag, metadata_last_modified, version)
→ GitHubConditionalRequest
→ If-None-Match / If-Modified-Since
├─ 200 → 映射权威 Metadata → 校验稳定 ID → refreshMetadata
│        → 更新元数据、校验器、last_verified_at、version+1
└─ 304 → Executor 作为成功返回 → markMetadataNotModified
         → 仅更新 last_verified_at、version+1
```

## 8. 建议阅读顺序

1. `GitHubIntegrationProperties` 与 `GitHubRestClientConfiguration`：先看边界和默认值。
2. `GitHubApiResponse`、`GitHubApiException`、`GitHubRateLimitSnapshot`：理解统一语义。
3. `GitHubApiEndpointPolicy`、`GitHubApiErrorDecoder`、`GitHubApiRetryPolicy`。
4. `GitHubApiHttpExecutor`：串起 Token、Semaphore、HTTP、Retry、日志、指标。
5. `RestClientGitHubRepositoryMetadataClient`：看业务 Client 如何保持薄。
6. `GitHubRepositoryBindingService.refreshRepositoryMetadata` 与 Mapper 两条 UPDATE。
7. V7 migration 与 Binding Integration Test。
8. 最后读第 8 节学习文档和各专项单元测试。

## 9. 不需要优先阅读的重复文件

`GitHubApiFailureType`、`GitHubConditionalRequest`、`GitHubPage`、`GitHubPageCursor`、
`GitHubRateLimitSnapshot`、`GitHubRepositoryEntity` 和 API Contract DTO 都是薄模型；理解字段后无需逐行阅读。
Mapper 的普通查询、测试 YAML 的重复 Base URL 覆盖、`GitHubTestProperties` 的构造参数也不是本节难点。

## 10. 关键 Diff 导读

- 先看 Executor：Authorization 从全局配置移到单次请求，304 从错误分支移到成功分支。
- 再看 Decoder + Retry：普通 403 与 Rate Limit 403 被分开，长等待停止同步 Retry。
- 再看 Link + Endpoint Policy：分页和重定向不能把 Bearer Token 带到任意 Host。
- 再看 Binding + Mapper + V7：200 更新权威元数据，304 只推进验证时间和 version。
- 最后看 Token Provider 与 Semaphore：数据库只有 reference，原始 Token 不作为 Key 或日志字段。
