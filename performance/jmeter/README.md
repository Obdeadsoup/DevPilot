# DevPilot JMeter 基线

这里保存可重复、人工触发的本地性能基线，不作为普通 PR Gate，也不代表生产容量。

## 前置条件

- DevPilot、MySQL 8、Redis 7 已启动，Flyway 已完成迁移。
- 准备一个有效用户和有权访问的 `WORKSPACE_ID`、`PROJECT_ID`。
- 本机安装 JMeter 5.6.x，并确保 `jmeter` 在 `PATH` 中。
- 只通过当前进程环境变量提供 Bearer Token：

```powershell
$env:ACCESS_TOKEN = '<local-access-token>'
```

Token 不进入 JMX、命令历史示例、JTL 或 HTML 报告。运行器只检查变量是否存在，并让 JMeter 在请求时从环境读取。

## 场景

- `devpilot-read-baseline.jmx`：依次读取 Workspace、Project、Activity、Task 和未读通知计数。
- `devpilot-task-workflow-baseline.jmx`：每个线程创建自己的 Task，并按响应版本执行 create → plan → start → submit-for-review；不会让线程共享 Task ID。

每个请求都断言 HTTP 200。鉴权、事务和日志保持开启；失败响应会计入 error rate。

## 运行

先用小流量 smoke：

```powershell
.\performance\jmeter\run-baseline.ps1 -Plan read -WorkspaceId 1 -ProjectId 1 -Threads 1 -RampSeconds 1 -DurationSeconds 15
```

再按 10 / 50 / 100 并发梯度人工运行。写场景会生成真实 Task，仅应对专用测试数据执行：

```powershell
.\performance\jmeter\run-baseline.ps1 -Plan task-workflow -WorkspaceId 1 -ProjectId 1 -Threads 10 -RampSeconds 10 -DurationSeconds 60
```

可用参数：`BaseUrl`、`WorkspaceId`、`ProjectId`、`Threads`、`RampSeconds`、`DurationSeconds`、`ResultsRoot`。默认 `BaseUrl` 为 `http://127.0.0.1:8080`。结果写入被 Git 忽略的时间戳目录，包含 JTL 和 HTML Dashboard。

## 观察与判读

在负载期间同时观察 `/actuator/prometheus` 或 `/actuator/metrics`：

- `http.server.requests`、`hikaricp.*`、`jvm.*`
- `devpilot.outbox.backlog`、`devpilot.outbox.oldest.ready.age`
- GitHub Delivery/Sync ready backlog、oldest ready age 和 open DEAD

报告至少记录 throughput、error %、p50/p95/p99/max，并把延迟变化与 CPU、heap、Hikari active/pending、backlog 和 oldest age 对照。若没有实际执行，应明确写 `NOT RUN`。
