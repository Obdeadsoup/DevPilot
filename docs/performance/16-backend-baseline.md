# 第 16 节：后端性能基线记录

## 环境

| 字段 | 记录 |
|---|---|
| Date | 2026-08-12 |
| Git commit | `26a346c` (`15可观测性`) |
| OS | Windows 11 amd64，Docker Desktop Linux Engine |
| CPU / RAM | Intel Core Ultra 7 255HX，20 cores / 20 logical processors；15.4 GiB RAM |
| JDK | Oracle JDK 21.0.12 |
| MySQL / Redis | 本机 Docker Desktop；MySQL 8.4 映射 3307，Redis 7.4 映射 6380 |
| JVM args | NOT RUN（计划使用应用默认参数） |
| duration / threads | NOT RUN；建议 smoke 1×15s，随后 10/50/100 并发梯度 |
| dataset size | NOT RUN / 未记录 |

## 结果

**NOT RUN**。本机当前没有可调用的 `jmeter` 命令，因此本节只交付可执行的 non-GUI 计划和运行器，没有安装工具，也没有虚构吞吐或延迟数据。

| 场景 | Throughput | Error % | p50 | p95 | p99 | Max |
|---|---:|---:|---:|---:|---:|---:|
| Read baseline | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Task workflow | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

这些本机数据即使后续补齐也只是学习环境基线，不能称为生产性能，不能外推“支持 X QPS”或 SLA。

## 执行时的 Metrics 联动

运行 `performance/jmeter/run-baseline.ps1` 时，应同步采集：

- HTTP：`http.server.requests` 的数量、错误和延迟；
- JVM：CPU、heap、GC；
- 数据库池：`hikaricp.connections.active/pending`；
- 可靠链路：`devpilot.outbox.backlog`、`devpilot.outbox.oldest.ready.age`，以及 Delivery/Sync ready backlog、oldest ready age、stale processing、open DEAD。

若 p95/p99 上升，先判断 JVM CPU/GC 是否饱和，再看 Hikari pending 是否增长、SQL 范围查询是否退化，以及 backlog/oldest age 是否持续积压。只看 TPS 无法区分应用、连接池和异步消费瓶颈。

## 待补记录

安装并人工运行 JMeter 后，在此补充：完整命令、应用 JVM args、数据库数据量、开始/结束时间、JTL/HTML 报告位置、上述 Metrics 的同时间窗快照和瓶颈结论。不得为提高数字关闭鉴权、事务或日志。
