# P0-09 Agent Resilience 与 Failure Model

## 目标与边界

本章把 AgentRun 在超时、显式取消、依赖故障和本机过载时的行为变成稳定状态机。实现不包含自动 Retry、跨实例 ActiveRunRegistry、Reconciliation、写 Tool 或伪造回答的 fallback。

## 两条调用链

取消链路：

```text
Browser
  → Gateway（普通短 HTTP 路由）
  → Core POST .../agent-runs/{runId}/cancel
  → CurrentUser + project scope + AGENT_PROPOSE
  → 确认 AgentRun=RUNNING
  → AgentRuntime.CancelRun(runId, requestId)
  → Python ActiveRunRegistry
  → CancellationToken.set
  → AgentLoop 下一安全 checkpoint 抛 RunCancelled
  → RUN_CANCELLED
  → Java 条件更新 RUNNING/version=0 → CANCELLED
  → SSE run-cancelled
```

熔断与容量链路：

```text
AgentRun start
  → Semaphore Bulkhead admission
  → CircuitBreaker permission（CLOSED/OPEN/HALF_OPEN）
  → gRPC StreamRun
  → async terminal/onCompleted = onSuccess
  → async transport onError = onError
  → 所有 terminal/error/cancel 路径释放 Bulkhead permit
```

故障传播：

```text
Python unavailable
  → gRPC UNAVAILABLE
  → AgentRun FAILED(UNAVAILABLE)
  → 连续失败累计
  → Circuit OPEN
  → 后续 Run FAILED(CIRCUIT_OPEN)，且不发网络请求
```

## Terminal 状态机与取消竞态

合法迁移只有：

```text
RUNNING → SUCCEEDED
RUNNING → FAILED
RUNNING → CANCELLED
```

三个终态均通过 `status='RUNNING' AND version=0` 条件更新竞争。CancelRun 必须先得到 Python `ACCEPTED`，Java 才竞争 `CANCELLED`；随后关闭本地 gRPC stream handle。若成功或失败已先落库，取消方读取并返回已有终态，不覆盖数据库，也不发布第二个 terminal SSE。已 `CANCELLED` 的 HTTP 取消是幂等读取，`SUCCEEDED/FAILED` 返回冲突。

Python 的 `ActiveRunRegistry` 是 thread-safe 的进程内注册表，拒绝 duplicate active runId，并用有界 terminal tombstone 回答近期重复取消。worker 真正退出后才清理 active entry；Python 重启后注册表丢失，多实例协调留待后续持久化方案。

## Timeout、Deadline 与 Cancel

- Deadline 表示调用方在期限内没有确认结果，不证明远端没有执行。
- Explicit Cancel 是经 RBAC 校验的业务动作，必须显式通知 Runtime。
- Transport Failure 表示网络或依赖基础设施失败，如 `UNAVAILABLE`。
- Runtime Failure 表示模型、Tool、步数预算或协议本身失败。
- Circuit Open 是本机根据历史故障作出的快速拒绝。
- Capacity Rejected 是本机同时活跃 Run 达上限后的 admission 拒绝。

Cooperative cancellation 不强杀线程，也不能瞬间终止正在阻塞的 DeepSeek HTTP 或 Tool gRPC。token 在 model 前/后、Tool 前/后和下一 step 前检查；当前阻塞调用返回或 timeout 后，cancel flag 会阻止任何后续 step/tool。

Timeout 分层保持：

```text
Tool RPC 3s
Provider connect 2s / read 30s / SDK overall 30s
Stream 10m
Gateway REST 10s / SSE response-timeout=-1
```

各层独立配置。SSE 不套短 REST timeout，Gateway SSE route 不加 CircuitBreaker。

## Circuit Breaker 与 Bulkhead

Core→Python 使用 Spring Cloud BOM 管理的非 Reactive Resilience4j starter，以及 Semaphore Bulkhead。默认配置为滑动窗口 10、最少调用 5、失败率 50%、OPEN 10 秒、HALF_OPEN 探测 2、单 Core 最大活跃 Run 20。

计入 breaker 的传输失败：`UNAVAILABLE`、`DEADLINE_EXCEEDED`、transport-like `INTERNAL/UNKNOWN`。`INVALID_ARGUMENT`、协议错误和用户取消不证明依赖不可用，不计为 dependency failure。OPEN 时先于 gRPC 快速失败。Bulkhead 解决“远端慢拖满本机并发”，Circuit Breaker 解决“远端持续坏仍不断撞击”，两者语义不同。

异步 Streaming 不能只在 `streamRun(...)` 方法上加注解：stub 很快返回，真实结果在后续 callback。Decorator 因而显式获取 CircuitBreaker/Bulkhead permission，并在 terminal、`onCompleted`、`onError` 或本地 cancel 时做一次性记账和释放。

Python→Java Tool Gateway 使用小型 `CLOSED/OPEN/HALF_OPEN` breaker，仅把 `UNAVAILABLE/DEADLINE` 计为依赖失败；权限、参数和业务失败说明 Java 有响应，不打开 breaker。OPEN 时不调用 stub。

## Retry 与 Fallback

本章不自动 Retry AgentRun、ToolCall 或 CancelRun：StreamRun 重试可能重复 LLM 消耗，ToolCall 会改变预算和 call-id 语义，CancelRun timeout 具有结果歧义。未来只有在持久幂等协议明确后才能按操作单独设计 Retry。

Fallback 只能降级状态和用户提示，不能在 Python 不可用时制造假的 AI 成功答案。依赖故障应快速落为稳定 failure kind，并由浏览器显示暂不可用。

## Failure taxonomy

数据库仅保存稳定名称，不保存 gRPC description、Provider body、Tool 参数或堆栈：

```text
MODEL_ERROR
TOOL_ERROR
MAX_STEPS
INVALID_ARGUMENT
PROTOCOL
UNAVAILABLE
DEADLINE_EXCEEDED
CIRCUIT_OPEN
CAPACITY_REJECTED
CANCEL_REQUEST_FAILED
INTERNAL
```

`CANCELLED` 是 status，不是 failure kind。兼容旧投影的 `REMOTE_FAILED/UNKNOWN` 暂时保留读取能力。

## 配置与指标

Java 配置前缀为 `devpilot.agent.grpc` 与 `devpilot.agent.resilience`；Python 通过 `DEEPSEEK_*_TIMEOUT_SECONDS`、`DEVPILOT_JAVA_TOOL_CIRCUIT_*` 读取参数。所有 secret 仍只来自环境变量。

低基数 Micrometer 指标：

```text
agent.runtime.circuit.calls{outcome}
agent.runtime.capacity.rejected
agent.run.cancel.requests
agent.run.cancel.accepted
agent.run.cancel.failed
agent.runtime.active.streams
```

禁止 runId、userId、projectId、toolCallId 作为标签。

## 验证与故障注入

普通测试覆盖取消权限与幂等、terminal race、stream handle、异步 breaker 记账、OPEN fast fail、容量释放、Python token checkpoint、registry 和 Tool breaker。`CrossLanguageAgentResilienceSmokeTest` 在显式开启 `DEVPILOT_AGENT_RESILIENCE_SMOKE=true` 时启动真实 Python TCP/HTTP2 进程，依次验证 Python down→OPEN、恢复→HALF_OPEN/CLOSED、slow fake cancel 和 capacity rejection。

本次实现的实际验收结果（2026-08-29）：

| 验证项 | 结果 | 说明 |
| --- | --- | --- |
| `mvn clean verify` | PASS | 12 个 Maven reactor 模块全部成功；Boot 共 120 项，107 项 Testcontainers 测试因本机 Docker 不可用而跳过 |
| `python -m pytest agent-service/tests` | PASS | 102 项 Python 测试通过 |
| `python -m ruff check agent-service` | PASS | 无 lint 错误 |
| `docker compose config --quiet` | PASS | Compose 配置可解析 |
| `CrossLanguageAgentResilienceSmokeTest` | PASS | 1 项、7.169 秒；真实 Python 子进程与 TCP/HTTP2，覆盖 down/open/fast-fail、recover/half-open/closed、slow cancel、capacity reject |
| Docker/Testcontainers 集成场景 | ENV SKIP | 当前环境无法连接 Docker daemon；非测试失败 |
