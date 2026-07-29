# 第 4 节：Spring Security 用户认证链路

## 1. 认证和授权不是一回事

认证（Authentication）回答“请求者是谁”。本节通过数据库用户、密码校验和 Redis Access Token
建立用户身份。授权（Authorization）回答“这个用户能否对这个资源执行这个动作”。当前只实现了
接口级的“公开、已认证、全部拒绝”规则，还没有 Workspace Member、角色、项目成员或资源归属判断。

因此，能够访问 `/api/v1/auth/me` 只说明 Token 对应一个有效用户；能够访问活动时间线目前也只说明
用户已登录，不能据此宣称 Workspace RBAC 已完成。

## 2. Filter 与 Interceptor

Servlet Filter 位于 `DispatcherServlet` 之前，能在请求进入 Spring MVC Controller 前建立安全上下文、
拒绝无效 Token，也能覆盖最终没有匹配到 Controller 的请求。Spring MVC Interceptor 位于
`DispatcherServlet` 内，更适合 Controller 调用前后的 MVC 逻辑。

认证必须尽早建立统一身份，并让 Spring Security 的授权过滤器使用，所以
`BearerTokenAuthenticationFilter` 继承 `OncePerRequestFilter`，而不是用 MVC Interceptor 或自定义
ThreadLocal 实现。

## 3. `SecurityFilterChain`

`SecurityConfiguration` 当前明确配置：

- 公开：`GET /actuator/health`、`POST /api/v1/github/webhooks`、`POST /api/v1/auth/login`；
- 要求认证：`GET /api/v1/auth/me`、`POST /api/v1/auth/logout`、活动时间线 GET；
- 其他请求：`denyAll`。

会话策略是 `STATELESS`，并关闭 form login、HTTP Basic、默认 logout、request cache 和 CSRF。API 只从
`Authorization: Bearer ...` 读取凭据，不使用浏览器自动携带的 Cookie Session，所以没有基于 Cookie 的
CSRF 攻击入口，本版本禁用 CSRF。若以后改成 Cookie 认证，必须重新评估并启用相应 CSRF 防护，不能照搬
当前配置。

## 4. `Authentication`

Spring Security 用 `Authentication` 同时表示认证请求和认证结果。登录前，
`UsernamePasswordAuthenticationToken.unauthenticated` 携带规范化 login 与原始密码，只用于本次密码
校验。认证成功后，应用把数据库安全字段转换为 `DevPilotUserPrincipal`。

Bearer 请求创建的是已认证 `UsernamePasswordAuthenticationToken`，其 principal 是安全 Principal，
credentials 为 `null`，authorities 当前为空。原始 Access Token 不进入 principal、credentials 或日志。

## 5. `AuthenticationManager` 与 `ProviderManager`

`AuthenticationService` 不自行查询密码 Hash，也不直接调用 `PasswordEncoder.matches`。它把未认证 Token
交给 `AuthenticationManager.authenticate`。当前 `AuthenticationManager` 是只包含一个
`DaoAuthenticationProvider` 的 `ProviderManager`。

这样认证编排遵循 Spring Security 标准扩展点：Manager 选择 Provider，Provider 负责用户名密码认证，
应用服务只负责规范化输入、统一外部错误和成功后签发 Access Token。

## 6. `DaoAuthenticationProvider`

`DaoAuthenticationProvider` 调用 `DatabaseUserDetailsService` 获取数据库凭据载体，再使用配置的
`PasswordEncoder` 校验密码，同时检查 locked/disabled 等 `UserDetails` 标志。

数据库凭据载体实现 `CredentialsContainer`，Provider 完成认证后会擦除内存中的密码 Hash。
`AuthenticationService` 随即只提取 id、username、email、displayName 创建安全 Principal；API DTO 和
Redis Session 都不包含 `passwordHash`。

## 7. `UserDetailsService`

`DatabaseUserDetailsService` 把 login 去除两端空白并按 `Locale.ROOT` 转成小写，然后通过 `UserMapper`
匹配 `username` 或 `email`。Mapper 只读取 `deleted = 0` 的用户。

用户不存在时抛出 `UsernameNotFoundException`。错误密码、用户不存在、LOCKED 和 DISABLED 在登录接口
外部都映射成相同的 `IDENTITY_0402` 与通用消息，避免通过响应枚举账号及其状态。

## 8. `PasswordEncoder`

配置使用 `PasswordEncoderFactories.createDelegatingPasswordEncoder()`。数据库保存值包含算法前缀，例如：

```text
{bcrypt}$2a$...
```

`{bcrypt}` 告诉 DelegatingPasswordEncoder 选择 BCrypt，后面的内容是带随机盐的单向密码 Hash，不是可解密
密文。Migration 不写固定用户、密码或 Hash；测试也在运行时调用 PasswordEncoder 动态生成 Hash。

登录 DTO 拒绝空白、超过 254 字符的 login 和超过 72 字符的密码，使异常大输入不会进入数据库查询或
BCrypt 计算。

## 9. `SecurityContextHolder` 与黑马点评 `UserHolder`

黑马点评课程中的 `UserHolder` 通常用自定义 ThreadLocal 保存当前用户，并要求拦截器在请求结束时手动
清理。Spring Security 的 `SecurityContextHolder` 承担相同的“当前执行线程身份”职责，但它由完整安全
过滤器链管理，并与授权、`@AuthenticationPrincipal`、匿名认证和异常处理集成。

Bearer Filter 使用 `SecurityContextHolder.createEmptyContext()` 创建新上下文再放入已认证对象，不复用
可能被污染的旧上下文，也不建立第二套 ThreadLocal。请求结束后的上下文清理由 Spring Security 负责。

## 10. 登录调用链

```text
POST /api/v1/auth/login
→ AuthController 校验 LoginRequest
→ AuthenticationService
→ AuthenticationManager / ProviderManager
→ DaoAuthenticationProvider
→ DatabaseUserDetailsService
→ UserMapper 查询 dp_user
→ DelegatingPasswordEncoder 校验
→ RedisAccessTokenService 签发 Token
→ 返回 Bearer Token、TTL 和安全用户信息
```

`username` 和 `email` 在数据库中有唯一约束与小写 CHECK。应用查询前再次规范化，数据库约束负责最终
数据不变量。

## 11. Bearer Token 请求链路

```text
Authorization: Bearer <opaque-token>
→ BearerTokenAuthenticationFilter
→ BearerTokenResolver 校验格式
→ 对原始 Token 计算 SHA-256
→ RedisAccessTokenService 查询会话
→ 创建安全 DevPilotUserPrincipal
→ 创建新的 SecurityContext
→ AuthorizationFilter 执行接口规则
→ Controller 使用 @AuthenticationPrincipal
```

没有 Authorization Header 时，Filter 继续执行，让后续授权规则决定公开接口放行还是保护接口返回
401。Header 存在但格式错误、Token 不存在或已过期时，Filter 直接使用 JSON EntryPoint 返回统一 401。
若上下文已有可信认证（例如安全测试注入），Filter 不会重复覆盖。

Webhook 不携带用户 Authorization Header 时不会触发 Token 查询，更不会通过数据库用户身份替代 GitHub
Webhook 的 HMAC 验签。Webhook 和用户认证是两条独立信任链。

## 12. 为什么第一版选择 Redis 不透明 Token，而不是 JWT

不透明 Token 本身不携带可读身份或权限声明，服务端以 Redis Session 作为实时事实来源。退出登录只需
删除 Key，Token 立即失效；账号身份字段也不会长期固化在自包含令牌中。这很适合当前单体、Redis 已存在、
需要简单撤销且暂不需要跨服务离线验签的阶段。

JWT 的优势是服务端可在不查询 Session Store 时验证签名，但退出撤销、权限变更即时生效、密钥轮换和
声明版本管理都需要额外设计。本节没有实际需求证明必须使用 JWT，因此不提前引入。

## 13. 原始 Token 与 Redis Token Hash

`RedisAccessTokenService` 使用 `SecureRandom` 生成 32 字节，也就是 256 bit 随机值，再以 URL-safe、无
padding Base64 编码。默认 TTL 为 2 小时，配置项是 `devpilot.identity.access-token-ttl`，校验范围为
大于 0 且不超过 24 小时。

原始 Token 只在登录响应和客户端后续 Authorization Header 中出现，不写数据库或日志，也不直接作为
Redis Key。Redis Key 为：

```text
devpilot:auth:access:{sha256(rawToken)}
```

Redis Value 是显式 JSON record，只包含 `userId`、`username`、`displayName`、`issuedAt`。ObjectMapper
针对具体 record 创建 reader/writer，并显式关闭默认多态类型。Token 恢复后的 Principal 不保存 email，
所以 Bearer `/me` 响应按当前设计省略 email；登录响应可从刚完成数据库认证的安全 Principal 返回 email。

## 14. 401 与 403

401 表示请求还没有建立可接受的身份，例如保护接口缺少 Token、Bearer 格式错误、Token 不存在或过期、
登录凭据错误。403 表示身份已经建立，但授权规则明确拒绝该动作，例如已登录用户访问 `denyAll` 的未定义
接口。

当前稳定错误分别包括 `AUTHENTICATION_REQUIRED`、`INVALID_CREDENTIALS`、
`INVALID_ACCESS_TOKEN`、`ACCESS_DENIED` 和不对外细分状态的 `ACCOUNT_UNAVAILABLE`。响应只包含稳定
`ApiResponse`，不会返回 Spring 异常、SQL、Redis 连接信息或堆栈。

## 15. `AuthenticationEntryPoint` 与 `AccessDeniedHandler`

`JsonAuthenticationEntryPoint` 处理无法建立身份的安全异常：缺失身份使用
`AUTHENTICATION_REQUIRED`，无效 Bearer 使用 `INVALID_ACCESS_TOKEN`。`JsonAccessDeniedHandler`
处理已认证但无权限的请求，返回 `ACCESS_DENIED`。

两者都通过 `SecurityErrorResponseWriter` 直接写 HTTP 状态和 `ApiResponse` JSON，因此不会出现 HTML
登录页、Basic challenge 或 302 表单登录跳转。

## 16. 为什么 `GlobalExceptionHandler` 覆盖不了全部 Filter 异常

`GlobalExceptionHandler` 是 Spring MVC 的 `@RestControllerAdvice`，处理 Controller 调用期间由
`DispatcherServlet` 捕获的异常。Bearer Filter 和 Spring Security 的授权 Filter 在 DispatcherServlet
之前执行；请求若在那里被拒绝，就根本没有进入 Controller，自然不会经过 MVC 异常解析器。

因此 Controller 层登录业务异常继续交给 `GlobalExceptionHandler`，Filter 层 401/403 必须使用
EntryPoint/DeniedHandler。这是执行层次差异，不是多加一个 `@ExceptionHandler` 就能解决的问题。

## 17. Workspace 角色不能写成全局 `ROLE_ADMIN`

同一个用户可能是 Workspace A 的 OWNER、Workspace B 的 MEMBER，并且只加入其中部分项目。把身份简单
写成全局 `ROLE_ADMIN` 会把某个 Workspace 内的权力错误扩散到整个系统，也无法表达项目级资源归属。

正确授权至少需要在每次业务访问中同时考虑当前用户、workspaceId、projectId、成员关系、角色和资源归属。
这些信息不能仅靠一个全局 authority 字符串解决，也不应提前塞入当前 Redis Token Session。

## 18. 当前仍未实现的安全能力

本节完成了数据库用户、密码登录、Redis 不透明 Access Token、Bearer Filter、当前用户、退出和统一
401/403，但仍未实现：

- 用户公开注册、找回密码和管理员用户管理；
- Workspace Member、OWNER/ADMIN/MEMBER/VIEWER RBAC；
- 项目成员、数据范围与资源归属校验；
- Refresh Token、JWT、OAuth2、验证码和 MFA；
- 登录失败限流、Token 管理列表和多设备会话策略；
- 权限变更后的会话批量撤销；
- 安全审计表、登录审计和 Agent 权限继承。

当前 logout 的 Redis 删除操作本身是幂等的；第一次退出后，同一个原始 Token 已不再是有效认证凭据，因此
再次调用受保护 logout 会得到统一 401，而不会产生 500。后续若要提供“撤销任意会话”能力，必须加入相应
身份确认、权限和审计，不能把 Token 管理接口匿名开放。
