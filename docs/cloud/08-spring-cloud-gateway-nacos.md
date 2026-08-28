# P0-08 Spring Cloud Gateway 与 Nacos

## 1. 结论与运行边界

本章新增一个真正可独立部署的 Java Edge Service：`devpilot-gateway`。原 `devpilot-boot` 改以服务名
`devpilot-core` 注册，但它仍是承载 identity、project、task、github、notification、audit、agent 等 Maven
模块的模块化单体。Maven module 是编译与代码边界，不自动等于 deployable service 或微服务。

```text
Browser
  → HTTP/SSE :8081 devpilot-gateway（WebFlux）
  → lb://devpilot-core
  → DiscoveryClient + Spring Cloud LoadBalancer
  → Nacos 中健康的 devpilot-core 实例
  → :8080 devpilot-core（Spring MVC 模块化单体）
```

Python AgentRuntime 仍是独立服务，但不引入 Nacos SDK：

```text
devpilot-core → Python AgentRuntime :50051（Run/StreamRun）
Python AgentRuntime → devpilot-core :50052（内部只读 Tool Gateway）
```

Gateway 面向 Browser HTTP/SSE，不是内部 gRPC 代理。双向 gRPC 保持明确端口、服务身份和协议边界，均不经过
Gateway。

## 2. 版本兼容关系

本章锁定：

| 层 | 版本 | 职责 |
|---|---:|---|
| Java | 21 | 语言和运行时 |
| Spring Boot | 3.5.16 | 应用、自动配置、Actuator |
| Spring Cloud | 2025.0.0 | Gateway、LoadBalancer、Discovery 抽象 |
| Spring Cloud Alibaba | 2025.0.0.0 | Nacos Discovery/Config 适配 |
| Nacos Server/Client | Server 3.0.3 / BOM 管理 Client 3.0.3 | 注册发现与非敏感配置 |

Spring Cloud 官方将 2025.0.x 对应到 Spring Boot 3.5.x；Spring Cloud Alibaba 官方兼容表明确给出
2025.0.0.0、Spring Cloud 2025.0.0、Spring Boot 3.5.0 和 Nacos Client 3.0.3 的组合。项目保留 Boot
3.5 系列的当前补丁版本 3.5.16，通过根 POM 的两个 BOM 统一依赖，不在 module 中单独写 starter 版本。
没有升级 Boot 4/Cloud 2025.1，也没有使用旧 `bootstrap.yml`、Ribbon、Hystrix、Zuul。

## 3. Gateway、Nginx 与 Core 的责任

Gateway 负责：显式 API 路由、CORS、Request/Correlation ID、普通 HTTP timeout、SSE timeout 例外、Actuator、
Nacos Discovery/Config。它不依赖业务模块、Mapper 或 MySQL，也不解析 Bearer Token、不复制 RBAC、不执行
AgentRun。

Nginx 更适合 TLS 终止、静态文件和通用 L4/L7 反向代理；Spring Cloud Gateway 更贴近 Java 服务发现、
LoadBalancer 和响应式 filter。当前新增 Gateway 不意味着生产环境不能在其前面再放 Nginx/WAF。

Core 仍是认证与授权权威。Gateway 原样转发 `Authorization`，Core 的 Security Filter 与 Application Service
继续校验 Workspace/Project scope。Prompt 或 Request ID 都不能提升权限。

## 4. 注册、发现与 `lb://`

`devpilot-core` 和 `devpilot-gateway` 是仅有的两个 Java deployable service，因此只有它们注册 Nacos。业务
Maven modules 不注册。Gateway 使用固定且可审计的显式 route：

```text
/api/** → lb://devpilot-core
```

没有启用 Discovery Locator，因此不会自动暴露 `/service-name/**`。`lb://` 不是一个固定 Core URL：Gateway
先让 DiscoveryClient 从 Nacos 获取 `devpilot-core` 实例，再由 Spring Cloud LoadBalancer 选择实例，最后保留原
API path 转发。Caffeine 只作为 Gateway 的生产级 LoadBalancer 本地缓存实现，不改变注册发现语义。

REST 调用链：

```text
Browser
→ Gateway route /api/**
→ lb://devpilot-core
→ DiscoveryClient / Spring Cloud LoadBalancer
→ Nacos instances
→ Core Controller
→ Core Application Service / RBAC
```

## 5. Request ID 与 CORS

Gateway 最外层 Filter 按以下顺序选值：

1. 合法 `X-Request-Id`（8..64 位安全字符）原样保留；
2. 否则复用合法 `X-Correlation-ID`；
3. 两者均无效时生成 UUID。

选中的同一个值写入下游请求和响应的两个 header，Core 现有 Correlation Filter 因而不会再生成第二个标识。
它只用于日志关联，不是 Run ID、鉴权凭据、幂等键或 metrics tag。

CORS 仅允许配置中的明确 Origin，默认 `http://localhost:5173`；允许 Authorization、Content-Type、两个请求
标识和 `Last-Event-ID`，不允许 credential cookie。生产 Origin 必须通过环境/Nacos 非敏感配置覆盖。

## 6. SSE 经过 Gateway

Agent Run 和 Notification SSE path 使用优先级更高的独立 route。普通 REST 全局 response timeout 是 10 秒，
SSE route metadata 的 `response-timeout=-1` 按 Spring Cloud Gateway 语义禁用该短超时；Gateway/WebFlux 不做
事件聚合。集成测试把全局 timeout 降到 100ms，并证明 200ms 后到达的首个 SSE 事件仍可收到。

```text
Browser Fetch/EventSource
→ Gateway SSE route（无短 response timeout）
→ Core SseEmitter
← Core EventHub / replay / heartbeat
← Java async gRPC Stream callback
← Python AgentRuntime
```

Gateway 不改变 Core 的 SSE 权威语义：断线补偿仍使用 GET AgentRun，Browser 断开仍不会取消 Python Run；
Bearer Token 继续放在 Authorization header，不进入 query string。

## 7. Nacos Config

配置链路：

```text
Nacos Config
→ spring.config.import
→ Spring Environment
→ @ConfigurationProperties / 普通 Spring 配置绑定
```

本项目不用 `bootstrap.yml`。约定如下：

| 环境 | Namespace | Group | DataId |
|---|---|---|---|
| local demo | public（空 namespace id） | `DEVPILOT` | `devpilot-core.yml`, `devpilot-gateway.yml` |
| dev/test/prod | 每环境独立 namespace id | `DEVPILOT` | 同上 |

`ops/nacos` 样例迁移了 Gateway CORS、普通 HTTP timeout marker，以及 Core 的 Agent deadline/GitHub timeout
等非敏感配置。数据库/Redis 密码、GitHub Secret、`DEEPSEEK_API_KEY`、Tool service key 等继续从进程环境或
专用 Secret Manager 注入。Config Center 提供配置分发与版本管理，不等于加密、轮换、最小权限审计完整的
Secret Manager。

两个 import 都显式 `refreshEnabled=false`。当前没有声称动态刷新：server port、gRPC channel target 等 Bean
初始化参数和本章所有 Nacos 配置均在服务重启后生效。

## 8. local 与 cloud Profile

- 默认、`local`、`test`：Nacos Config/Discovery 关闭，import 为 `optional:nacos:`，Nacos 不存在也能进行原
  基础开发和测试。
- `cloud`：Config import 去掉 optional，Config/Discovery 和对应 Health Indicator 显式启用，Discovery
  fail-fast；Nacos 或 DataId 不可用时启动失败。

Core 继续默认监听 8080，Gateway 默认 8081。Nacos Console 默认映射 8082，Open API 8848，客户端 gRPC
9848。`cloud` Compose profile 只增加 Nacos，不强迫 MySQL/Redis 基础流程依赖它。

## 9. 本地 Compose 验收

先准备 `.env` 中 MySQL/Redis 的本地密码，再执行：

```powershell
docker compose config
docker compose --profile cloud up -d mysql redis nacos
docker compose --profile cloud ps
.\ops\nacos\Publish-DevPilotNacosConfig.ps1
mvn -pl devpilot-boot,devpilot-gateway -am package -DskipTests
```

分别启动：

```powershell
java -jar .\devpilot-boot\target\devpilot-boot-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,cloud
java -jar .\devpilot-gateway\target\devpilot-gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=cloud
.\ops\nacos\Test-DevPilotCloudSmoke.ps1
```

脚本检查 Nacos health、两个 DataId 的 marker、两个注册服务、Gateway health/config marker 和非 API 404。
真实 API/Agent E2E 还需要本地账号、项目数据、Python FakeModel 与 Tool service key：把原有 AgentRun POST/GET/SSE
URL 的 host 从 Core 8080 改成 Gateway 8081；Python :50051 与 Java Tool :50052 地址保持不变。Nacos Console
可在 `http://localhost:8082` 查看实例。

生产环境不得沿用 Compose 的 `NACOS_AUTH_ENABLE=false`。必须启用鉴权、注入真实环境凭据、限制 8848/9848
网络边界并为 namespace/账号配置最小权限。

## 10. 为什么本章不增加更多中间件

- 不加 OpenFeign：Java 业务模块尚未拆成进程间 REST，没有真实 Service A → B 场景。
- 不加 Sentinel：限流/熔断将在 P0-09 结合真实失败语义设计，不用占位规则制造虚假可靠性。
- 不加 Seata：业务数据仍由一个 Core/一个数据库 owner 管理，没有跨数据库分布式事务。
- Python 不注册 Nacos：Core 到 Python 的 gRPC target 已配置化；引入第二套语言 SDK不会改善当前单一调用边界。
- ToolGateway 不走 Gateway：它是带 service key 的内部 gRPC，只读工具执行还要在 Core 恢复用户/scope 并重做
  RBAC；转成 Browser HTTP 会模糊信任边界。

## 11. 自动化测试覆盖

Gateway 测试不连接真实 Nacos，使用 Spring Cloud SimpleDiscoveryClient 提供进程内 Core 实例，覆盖：

- `/api/**` 实际经过 `lb://devpilot-core`；
- Authorization 透传；
- Request ID 保留、回退、UUID 生成与 Core correlation 同值；
- CORS preflight；
- SSE route 不受普通 REST 短 timeout 影响；
- 非 API 404、Gateway health；
- Gateway 不依赖业务/持久化/Servlet MVC 的 ArchUnit 边界。

真实 Nacos Registry、Config load、Gateway → Core 和跨语言 Agent E2E 属于环境 smoke，必须按实际执行结果标记
PASS、FAIL、NOT RUN、BLOCKED 或 ENV SKIP，不能由单元测试结果推断。

### 本次实现的实际结果（2026-08-28）

| 检查 | 结果 | 事实 |
|---|---|---|
| `mvn clean verify` | PASS | 12 个 reactor 项目全部成功；Gateway 10 tests 通过 |
| Python `pytest` | PASS | 隔离环境中 90 passed |
| Python Ruff | PASS | `All checks passed!` |
| 默认/`cloud` Compose config | PASS | 两种 `docker compose ... config --quiet` 均退出 0 |
| Java → Python fake gRPC unary/stream | PASS | 独立 Python 进程，`CrossLanguageGrpcSmokeTest` 1 passed |
| Python → Java Tool gRPC | PASS | 独立 Python 进程，`CrossLanguageToolGatewaySmokeTest` 1 passed |
| Gateway → 进程内 Core REST/SSE | PASS | SimpleDiscoveryClient + LoadBalancer，无固定 Core URL |
| 真实 Nacos Registry/Config/Gateway/Core | ENV SKIP | Docker Desktop daemon 不可连接；未虚构容器结果 |
| Gateway → Core → Python → Tool → SSE 全链路 | ENV SKIP | 依赖上述 Nacos 与 MySQL/Redis 容器环境 |

## 12. 官方依据

- Spring Cloud 支持版本：<https://spring.io/projects/spring-cloud/>
- Spring Cloud 2025.0 Gateway starter/config prefix 变化：<https://spring.io/blog/2025/05/29/spring-cloud-2025-0-0-is-available/>
- Spring Cloud Alibaba 版本矩阵：<https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/>
- SCA Nacos Config/Discovery quick start：<https://sca.aliyun.com/en/docs/2025.x/user-guide/nacos/quick-start/>
- Gateway route timeout：<https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html>
- Nacos Docker：<https://github.com/nacos-group/nacos-docker>
- Nacos 3 端口：<https://www.nacos.io/en/docs/v3.0/manual/admin/deployment/deployment-overview/>
