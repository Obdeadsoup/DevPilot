# 第 16 节：Backend Freeze Checklist

状态以 2026-08-12 本轮实际验证为准；`[ ]` 表示尚未验证或明确暂缓，不以文档代替证据。

## Build

- [x] `mvn clean verify` 可重复通过
- [x] ArchUnit 定向测试通过
- [x] 核心 Testcontainers 真实通过（MySQL/Redis，0 skipped）
- [x] `docker compose config` 通过

## Database

- [x] Flyway 文件保持 V1→V13 顺序，本轮未新增或修改 migration
- [x] 新环境由 Testcontainers 从空库完整 migrate 到 V13
- [x] 没有生产 seed 密码；示例/测试 credential 不作为生产值

## Security

- [x] 认证与 Scope RBAC 有 401/403 集成覆盖
- [x] Workspace/Project 范围进入授权与 SQL 条件
- [x] Secret/Token 不写日志、不入性能计划
- [x] Actuator 只暴露约定 endpoint，liveness/readiness 与业务依赖语义分离
- [x] DEAD replay/admin 操作受 scope/RBAC、reason 和资源状态约束

## Reliability

- [x] GitHub Delivery 有持久化、幂等、有限重试、stale recovery 和 DEAD
- [x] GitHub Sync 有 checkpoint、有限重试、恢复和人工 replay
- [x] Outbox 有事务落库、claim、有限重试、stale recovery 和 DEAD
- [x] DEAD replay 生成 SUCCESS/FAILURE/DENIED Audit
- [x] Notification 以唯一 dedupe key 防重复，REST 是可靠来源

## Observability

- [x] Actuator health 与 liveness/readiness
- [x] Prometheus-ready endpoint
- [x] Delivery/Sync/Outbox backlog 与 oldest ready age
- [x] open DEAD 与 historical DEAD 分离
- [x] HTTP 和有限异步链路 Correlation ID

## Performance

- [ ] JMeter baseline 实跑（当前 `NOT RUN`：本机无 JMeter）
- [x] 环境与未运行原因已记录
- [x] 未虚构 QPS、SLO 或 SLA

## Documentation

- [x] README、architecture、database design、roadmap 与第 16 节边界同步
- [x] 测试矩阵、性能方案、学习文档和文件地图已建立
- [x] 当前限制明确记录，不使用“生产就绪”表述
