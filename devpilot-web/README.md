# DevPilot Web 前端工程 (devpilot-web)

DevPilot 项目的专用前端工程，基于 Vue 3 + TypeScript + Vite + Element Plus 构建，用于本地接口测试与开发联调。

## 前置环境要求

- Node.js `18.0+` 或 `20.0+`
- npm `9.0+`
- JDK 21、Maven 3.6.3+ 与 Docker (用于运行 DevPilot Spring Boot 后端)

## 快速开始

### 1. 安装依赖

```powershell
Set-Location .\devpilot-web
npm install
```

### 2. 启动开发服务器

```powershell
npm run dev
```

前端服务默认运行于 `http://localhost:5173`。

### 3. 类型检查与构建验证

```powershell
# 运行 TypeScript 严格类型检查 (vue-tsc --noEmit)
npm run typecheck

# 构建生产环境产物
npm run build
```

---

## 本地后端启动与 Vite 代理配置

### 启动 DevPilot 后端

后端默认地址为 `http://127.0.0.1:8080`。

1. 在项目根目录根据模板准备环境配置：
   ```powershell
   Copy-Item .env.example .env
   ```
2. 启动 MySQL (`3307`) 与 Redis (`6380`) 容器：
   ```powershell
   docker compose up -d
   ```
3. 编译并启动后端 Boot 服务：
   ```powershell
   mvn -pl devpilot-boot -am install -DskipTests
   java -jar .\devpilot-boot\target\devpilot-boot-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
   ```
4. 验证后端健康状态：`GET http://127.0.0.1:8080/actuator/health`

### Vite 开发代理

前端应用代码仅使用相对路径（如 `/api/v1/auth/login` 或 `/actuator/health`）。`vite.config.ts` 已配置开发代理：

```ts
server: {
  port: 5173,
  proxy: {
    '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    '/actuator': { target: 'http://127.0.0.1:8080', changeOrigin: true },
  },
}
```

---

## 本地账号注册与登录

首次运行不再需要手工写入 `dp_user`。浏览器可通过 `/register` 创建本地 ACTIVE 用户，服务端使用现有 Spring
`DelegatingPasswordEncoder` 写入 `{bcrypt}...` 密码 Hash；注册成功后跳转到 `/login`，登录支持用户名或邮箱。
Access Token 统一由 Pinia Auth Store 保存于 `sessionStorage`，每次页面恢复都会请求 `/api/v1/auth/me` 复核，失效时清理本地状态并回到登录页。

---

## 功能模块与页面索引

| 页面 / 路由 | 核心功能 | 对应后端真实 API |
|---|---|---|
| `/register` | 注册本地账号（3–64 位用户名、邮箱、12 位以上含字母数字密码） | `POST /api/v1/auth/register` |
| `/login` | 用户名/邮箱 + 密码登录，获得 Bearer Token | `POST /api/v1/auth/login` |
| `/me` | 当前登录用户资料查询，撤销 Session 退出 | `GET /api/v1/auth/me`、`POST /api/v1/auth/logout` |
| `/health` | 系统健康检查 | `GET /actuator/health` |
| `/workspaces` | Workspace 列表与分页 | `GET /api/v1/workspaces` |
| `/workspaces/new` | 创建新 Workspace | `POST /api/v1/workspaces` |
| `/workspaces/:id` | Workspace 详情/资料更新/禁用/恢复 | `GET/PUT /api/v1/workspaces/{id}`、`POST .../disable`、`POST .../reactivate` |
| `/workspaces/:id/projects` | 项目列表与状态/可见性筛选 | `GET /api/v1/workspaces/{id}/projects` |
| `/workspaces/:id/projects/new` | 创建新项目 (Key 一旦创建不可更改) | `POST /api/v1/workspaces/{id}/projects` |
| `/workspaces/:id/projects/:id/overview` | 项目详情/更新/激活/归档/恢复 | `GET/PUT .../projects/{id}`、`POST .../activate` / `archive` / `restore` |
| `/workspaces/:id/projects/:id/repositories` | GitHub 仓库绑定列表与绑定新仓库 | `GET/POST .../github-repositories` |
| `/workspaces/:id/projects/:id/repositories/:id` | 仓库绑定详情/刷新/禁用/重新启用/解绑 | `GET .../github-repositories/{id}`、`POST .../refresh` / `disable` / `reactivate` / `unbind` |
| `/workspaces/:id/projects/:id/activities` | Activity 变更时间线 | `GET .../activities` |
| `/workspaces/:id/projects/:id/github/issues` | Issue 快照列表与详情 | `GET .../github/issues` & `GET .../github/issues/{id}` |
| `/workspaces/:id/projects/:id/github/pull-requests` | PR 快照列表与详情、Review 列表 | `GET .../pull-requests` & `GET .../pull-requests/{id}` & `GET .../reviews` |
| `/workspaces/:id/projects/:id/sync-runs/...` | 手工触发 Commit 同步与单 Run 状态短轮询 | `POST .../sync/commits` & `GET .../sync-runs/{runId}` |
| `/workspaces/:workspaceId/projects/:projectId/agent` | 创建 Agent Run、Cancel、展示 Java Core SSE 生命周期事件 | `POST/GET .../agent-runs`、`POST .../cancel`、`GET .../stream` |
| `/developer-console` | 开发者联调日志、Header/Body 脱敏、curl 导出 | 内存审计 log 视图 |

---

## 安全规则与规范

1. **凭据安全**：禁止在前端源码、注释、默认值或日志中硬编码真实 Token、Secret 或密码。
2. **凭据引用**：`apiCredentialRef` 与 `webhookSecretRef` 是服务器宿主环境变量名称引用，非明文凭据。
3. **外部不可信内容**：GitHub Issue/PR/Review 正文为外部不可信内容，严禁使用 `v-html`，均经过安全脱敏渲染。
4. **禁止任意请求**：开发者控制台仅允许对预定义 GET 接口再次刷新，不提供任意 URL 或 Header 发起器。
5. **Webhook 说明**：`POST /api/v1/github/webhooks` 为 GitHub 回调接口，前端仅做静态文档说明，不提供发送按钮。
