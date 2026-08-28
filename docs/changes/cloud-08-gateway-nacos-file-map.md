# P0-08 Gateway/Nacos 文件地图

标记：`[NEW]` 新增，`[MOD]` 修改，`[DEL]` 删除。本章无删除文件。

## 构建与共享基础

- `[MOD] pom.xml`：加入 `devpilot-gateway` reactor；集中导入 Spring Cloud 2025.0.0 与 Spring Cloud Alibaba
  2025.0.0.0 BOM。它只定义构建依赖关系，不代表每个 Maven module 都是服务。
- `[MOD] devpilot-framework/.../CorrelationIdPolicy.java`：公开复用安全格式校验；属于中立 framework，供 Core
  Servlet Filter 与 Gateway WebFlux Filter 使用，不包含业务或网络发现逻辑。
- `[MOD] devpilot-boot/pom.xml`：Core 增加 Nacos Config/Discovery client；没有加入 Gateway starter。

## devpilot-core（deployable service）

- `[MOD] devpilot-boot/src/main/resources/application.yml`：服务名改为 `devpilot-core`；默认 optional import，
  Config/Discovery/Health 均关闭，保留无 Nacos开发路径。
- `[NEW] devpilot-boot/src/main/resources/application-cloud.yml`：cloud 下 required import、Discovery fail-fast、
  显式 Nacos Health；配置修改后重启生效。
- `[MOD] devpilot-boot/src/test/resources/application-test.yml`：测试明确禁用 Nacos，避免依赖外部 Registry。

Core 仍组合所有业务 Maven module并拥有 Spring Security、RBAC、Application Service、Mapper、MySQL、Redis。

## devpilot-gateway（deployable service）

- `[NEW] devpilot-gateway/pom.xml`：WebFlux Gateway、LoadBalancer/Caffeine、Nacos、Actuator；唯一内部依赖是
  `devpilot-framework`，没有业务模块、MyBatis 或数据库依赖。
- `[NEW] .../GatewayApplication.java`：独立 Spring Boot 入口。
- `[NEW] .../config/GatewayCloudConfiguration.java`：装配无业务语义的共享请求标识策略和 CORS properties。
- `[NEW] .../config/GatewayCorsProperties.java`：绑定明确 Origin 与 preflight max-age。
- `[NEW] .../config/GatewayCorsConfiguration.java`：统一浏览器 CORS；允许 Bearer/Last-Event-ID，不启用 cookie
  credentials。
- `[NEW] .../config/GatewayRequestIdFilter.java`：安全选择/生成请求 ID，并让 `X-Request-Id` 与
  `X-Correlation-ID` 在 Gateway/Core 调用链中同值；不作鉴权或 metrics tag。
- `[NEW] devpilot-gateway/src/main/resources/application.yml`：显式 `/api/** → lb://devpilot-core`，关闭自动
  Discovery Locator；SSE route 禁用短 response timeout；默认 Nacos optional/off。
- `[NEW] devpilot-gateway/src/main/resources/application-cloud.yml`：cloud required Config/Discovery 与 Health。
- `[NEW] devpilot-gateway/src/test/resources/application-test.yml`：隔离真实 Nacos。

网络调用关系：Browser → Gateway :8081 → Nacos/LoadBalancer 发现的 Core :8080。Authorization 透明转发，
Gateway 不访问 Mapper/MySQL，不拥有 Workspace/Project 权限判断。

## Gateway 测试

- `[NEW] .../GatewayRequestIdFilterTest.java`：合法 ID、fallback、过长/非法值生成 UUID。
- `[NEW] .../GatewayIntegrationTest.java`：以进程内 Reactive Core + SimpleDiscoveryClient 验证负载均衡路由、
  Authorization、请求 ID、CORS、SSE timeout、404、health 与 route metadata；不连接 Nacos。
- `[NEW] .../GatewayBoundaryArchitectureTest.java`：禁止 Gateway 依赖业务模块、Persistence/Mapper、Servlet MVC。

## Nacos/运维边界

- `[MOD] compose.yaml`：新增可选 `cloud` profile 的 Nacos 3.0.3 standalone；不改变 MySQL/Redis 默认服务。
- `[MOD] .env.example`：公开 Nacos/Gateway 变量名与本地非敏感默认，真实生产凭据仍只从环境注入。
- `[NEW] ops/nacos/devpilot-core.yml`：Core 非敏感 Nacos Config 样例。
- `[NEW] ops/nacos/devpilot-gateway.yml`：Gateway CORS 与 config marker 样例。
- `[NEW] ops/nacos/Publish-DevPilotNacosConfig.ps1`：发布两个 DataId，不读取或上传 Secret。
- `[NEW] ops/nacos/Test-DevPilotCloudSmoke.ps1`：检查 Registry、Config、Gateway health 与边界；结果取决于真实环境。

约定：local 使用 public namespace、`DEVPILOT` group、两个服务名对应 DataId；dev/test/prod 使用独立 namespace。
Nacos Config 不是 Secret Manager。Python Agent 不注册 Nacos，Core → Python :50051 与 Python → Core :50052
继续绕过 Browser Gateway。

## 项目说明

- `[MOD] README.md`：补充真实部署边界、版本、Gateway/Nacos 启动和请求入口。
- `[MOD] .gitignore`：仅放行本章两份受控文档。
- `[NEW] docs/cloud/08-spring-cloud-gateway-nacos.md`：版本、架构、Discovery/Config、SSE、安全与验收说明。
- `[NEW] docs/changes/cloud-08-gateway-nacos-file-map.md`：本文件。

配置调用链：`Nacos Config → spring.config.import → Spring Environment → @ConfigurationProperties`。
Agent 调用链：`Core → Python :50051`、`Python → Core :50052`；两条 gRPC 链路都不经过 Gateway。
