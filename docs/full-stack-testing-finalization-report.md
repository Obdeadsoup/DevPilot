# DevPilot 全链路可测试化验收报告

## 1. Baseline

- Branch：`agent`
- Before SHA：`5ce0b4abedf89f845a49a6f862f06e1defd378f4`
- After SHA：未创建 commit（按变更控制要求不提交）；当前 HEAD 仍为上述 SHA，修改位于工作树。
- 开始时 `git status --short --branch` 只有 `## agent`，没有用户未提交修改。

## 2. Existing capabilities discovered

- `dp_user`（V3 Flyway）已经有小写 username/email 的唯一索引、`password_hash VARCHAR(255)` 与 ACTIVE/LOCKED/DISABLED 状态。
- Identity 已有 `DelegatingPasswordEncoder`（BCrypt）、Redis 保存 SHA-256 后的随机不透明 Bearer Token、Filter、`SecurityContextCurrentUserProvider` 和 scoped RBAC；不是 JWT。
- Gateway 路由透明转发 `Authorization`，SSE 路由关闭短 response timeout；Core 才执行认证和 Workspace/Project RBAC。
- Project、GitHub、Task、Notification 已分别具有 Controller 和现有 Vue 页面。Java Agent 已有 Start/Get/Cancel 与 gRPC StreamRun → SSE，缺少前端入口。

## 3. Gaps found

1. Identity 没有公开注册；README 要求手工插入用户。
2. 前端只有登录页，且明确声称不能注册；恢复 Token 不会调用 `/me` 验证。
3. 没有 Agent API 模块、Agent Run 页面或带 Authorization Header 的 Agent SSE 客户端。
4. `agent-service` 的 generated gRPC Stub 要求 `grpcio>=1.83.1`，但 `pyproject.toml` 曾允许 `1.83.0`。

## 4. Authentication design

本次保留既有 Redis 不透明 Token，未强行改为 JWT，以免破坏可立即撤销的既有认证模型。`POST /api/v1/auth/register` 只创建 ACTIVE 用户并返回安全 `UserResponse`，不自动签发 Token；用户再走原来的 `/login`，保持唯一登录链。密码只进入 `PasswordEncoder.encode`，响应和日志都不包含密码或 Hash。数据库 V3 已经满足数据兼容、字段长度和 unique index 要求，因此没有新增 migration。

## 5. Frontend completed features

- `/register`：校验用户名、邮箱、密码强度和确认密码，冲突/验证错误可见，注册成功回到登录页。
- Auth Store：统一保存/清理 Token；首次路由守卫调用 `/me` 复核并恢复用户，401 统一登出并跳转登录。
- 新增 Project scoped Agent 页面：启动、取消、状态、终态输出、loading/error/empty event state。
- Fetch SSE 使用 `Authorization` Header，不使用 query token；忽略 heartbeat，保留 Last-Event-ID，replay-gap/terminal 时用一次 GET 读取权威状态；离开页面会释放浏览器连接且不会取消 Agent Run。

## 6. BACKEND_API_ADDITIONS

| 项目 | 内容 |
|---|---|
| 为什么新增 | 已有数据库、密码编码与登录，但新用户没有可从浏览器创建账号的正式入口。 |
| 原有缺口 | 必须手工写 MySQL 并生成密码 Hash，无法完成注册→登录闭环。 |
| Endpoint | `POST /api/v1/auth/register` |
| Request | `{ "username": "...", "email": "...", "password": "..." }`；用户名 3–64 位，邮箱格式正确，密码 12–72 位且含字母与数字。 |
| Response | `201` + `ApiResponse<UserResponse>`：id、username、email、displayName；没有 password/passwordHash。 |
| 权限 | 匿名；Security 仅放行这一条公开注册路径，其他业务接口仍认证。 |

## 7. Agent UI + SSE chain

Agent 前端只访问相对的 `/api/v1/.../agent-runs` 路径，由 Vite/Gateway 到 Java Core；Java `AgentRunApplicationService` 保持创建者与项目范围，gRPC Adapter 再调用 Python。Gateway 已有独立 SSE route 和无限 response timeout，未新增会 buffer response 的 filter。事件名称严格使用 Core：`run-started`、`model-step-started`、`tool-started`、`tool-completed`、`run-succeeded`、`run-failed`、`run-cancelled`。

## 8. Security checks

- 无固定 userId；身份来自 Bearer Filter 的 Principal，Project 权限仍在应用服务中执行。
- 注册前检查提升 UX，数据库 unique index 是并发最终防线。
- 未新增第二张 User 表、JWT Secret、Cookie Session 或 Python Browser endpoint。
- Agent 浏览器连接只带 Bearer 给 Java；Python 不接收浏览器 Token。
- 生成代码没有被手工修改；Python 依赖下限与已有 generated Stub 对齐。

## 9. Test commands and actual results

| Command | Result |
|---|---|
| `npm install --ignore-scripts` | pass（前端依赖无变更）。 |
| `npm run build` | pass；`vue-tsc -b && vite build` 完成。Vite 提示历史大 bundle warning，不影响构建。 |
| `mvn clean verify` | pass；完整 Reactor 已产出 Boot/Gateway jar，所有 Surefire XML 无 failure/error。Testcontainers 场景均 `disabledWithoutDocker` 跳过，包括新增注册集成测试。 |
| `mvn -pl devpilot-identity test` | pass；15 tests，含新增 RegistrationService 成功及重复用户名/邮箱测试。 |
| `.venv\\Scripts\\python.exe -m ruff check agent-service` | pass。 |
| `.venv\\Scripts\\python.exe -m pytest agent-service/tests` | blocked：升级至契约要求的 grpcio 1.83.1 后，Windows Application Control 阻止 `cygrpc` DLL 加载。升级前则被 generated Stub 明确拒绝 1.83.0。 |
| `docker compose config` | pass。 |
| `docker compose ps` | blocked：Docker Desktop Linux Engine named pipe 不存在。 |

## 10. E2E results

不能宣称全链路 smoke 已通过。Docker 未运行使 MySQL、Redis、Core、Gateway 和真实 Agent 服务均无法启动，故 Case 1–10 没有伪造成功结果。新增的 Auth Testcover register success、duplicate username/email、bad email/password、login、`/me`、401/invalid token；现有 RBAC 测试覆盖跨用户 Project 403，Agent Controller 测试覆盖成功/失败/取消与 SSE。恢复 Docker 后应按 README 的 Compose/Core/Gateway/Python fake runtime 启动步骤依次执行任务要求中的 Case 1–10。

## 11. Known limitations

- 当前 Agent 服务默认真实模型需要本地 Provider Key；可用 `AGENT_MODEL_MODE=fake` 做确定性 E2E，不提交 Key。
- Agent 没有列表 API，前端显示当前页面启动的 Run；这与现有 Controller 契约一致，未臆造列表接口。
- Python `grpcio 1.83.1` 被本机 Windows 应用控制策略拦截，需要在允许该 wheel 的开发环境或 CI 上运行 Python/gRPC E2E。

## 12. UPDATED_FILE_MAP

### 新增

| Path | 职责、为什么新增 | 调用方 / 依赖方 |
|---|---|---|
| `devpilot-identity/.../RegisterRequest.java` | 注册输入与 Jakarta 校验。 | AuthController / Validation。 |
| `devpilot-identity/.../RegistrationService.java` | 规范化、唯一性、BCrypt Hash、创建安全 Principal。 | AuthController / UserMapper、PasswordEncoder。 |
| `devpilot-web/src/views/RegisterView.vue` | 浏览器注册表单。 | Router / auth API。 |
| `devpilot-web/src/api/modules/agent.ts` | 已有 Agent Controller 的强类型 HTTP 映射。 | AgentRunView / client。 |
| `devpilot-web/src/services/agentRunStream.ts` | Fetch + Header 的 Agent SSE 读取器。 | AgentRunView / Core SSE。 |
| `devpilot-web/src/views/agent/AgentRunView.vue` | Run 创建、取消、事件与状态可视化。 | Router、Agent API/SSE。 |
| `docs/full-stack-testing-finalization-report.md` | 本次审计、调用链、结果与阻塞证据。 | 维护者。 |

### 修改

| Path | 修改内容、原问题、修改后作用 |
|---|---|
| `AuthController.java` | 增加 register；原来只能手工建用户，现在返回安全 DTO。 |
| `UserMapper.java`、`IdentityErrorCode.java` | 增加创建/占用查询与 409 错误；复用原表约束。 |
| `SecurityConfiguration.java` | 精确放行匿名 register；其余 protected API 不变。 |
| `AuthenticationSecurityIntegrationTest.java` | 注册、重复、弱密码、Hash 与后续登录测试。 |
| `agent-service/pyproject.toml` | grpcio 下限升至 1.83.1；消除声明与 Stub 的不一致。 |
| `devpilot-web/src/stores/auth.ts`、`router/index.ts` | `/me` 驱动的恢复和异步 guard。 |
| `devpilot-web/src/api/modules/auth.ts`、`types/api.ts` | 注册和 Agent 契约类型。 |
| `LoginView.vue`、`MainLayout.vue`、`devpilot-web/README.md` | 注册入口、Agent 导航和当前使用说明。 |

## 13. Full call chains

```text
注册
RegisterView → Gateway → AuthController → RegistrationService
→ PasswordEncoder → UserMapper → dp_user (MySQL)

登录
LoginView → Gateway → AuthController → AuthenticationService
→ DaoAuthenticationProvider/PasswordEncoder → RedisAccessTokenService
→ Browser sessionStorage

普通认证请求
Browser → Authorization: Bearer → Gateway（透明转发）
→ BearerTokenAuthenticationFilter → SecurityContext/CurrentUser
→ RBAC Application Service → Business Service

Agent
AgentRunView → Gateway → AgentRunController → AgentRunApplicationService
→ gRPC Adapter/Stub → Python grpc.Server → Agent Runtime/LLM/Tools
→ StreamRun → Java AgentRunEventHub SSE → Gateway → Fetch SSE Browser
```

## 14. Key diff walkthrough and recommended reading order

登录前不可用的根因是已有安全模型只支持“预先存在的用户”，而前端也没有注册入口；本次把注册放进同一个 User/PasswordEncoder/Principal 体系。不是 JWT：既有随机 Token 的 Hash 存 Redis，Filter 解析它建立 CurrentUser，Gateway 不信任或解析业务 userId，只透传 Header。前端的 Auth Store 是 Token 唯一拥有者，路由进入前 `/me` 复核，401 会统一清理。Agent 之前缺少 UI/API/SSE client；现在由 Header-based Fetch SSE 直连 Gateway/Core，展示真实生命周期，而不是轮询或直接调用 Python。

推荐阅读：

1. `AuthController`
2. `RegistrationService`
3. `UserMapper` 与 V3 migration
4. `BearerTokenAuthenticationFilter`
5. `SecurityConfiguration` 与 Gateway route
6. `devpilot-web/src/stores/auth.ts`
7. `devpilot-web/src/api/client.ts`
8. `LoginView` / `RegisterView`
9. `AgentRunView`
10. `agentRunStream.ts`
