# 第 16 节文件地图：后端收尾、CI、ArchUnit 与性能基线

## 变更清单

- `[MOD] pom.xml`：集中定义 ArchUnit 1.4.2 版本。
- `[MOD] devpilot-boot/pom.xml`：在完整模块装配层加入 ArchUnit JUnit 5 test dependency。
- `[NEW] devpilot-boot/src/test/java/com/obdeadsoup/devpilot/architecture/*Test.java`：模块、分层和跨模块 Persistence 守护。
- `[NEW] IdentityUserEligibilityService`、`ProjectTaskContextQuery/ProjectTaskContext` 及实现：替代 Task 对其他模块 Mapper/Entity 的直接访问。
- `[MOD] Task application services`：只通过 Identity Application Service 与 Project Port/DTO 跨模块协作。
- `[MOD] boot integration test profile/tests`：按真实依赖收敛 readiness/Scheduler，并修正 UTC 造数、自引用清理顺序和 Activity 真实来源列断言。
- `[NEW] .github/workflows/backend-ci.yml`：PR/main push/manual 的 Backend CI。
- `[NEW] docs/testing/16-backend-test-matrix.md`：测试分层、依赖和 CI 范围。
- `[NEW] performance/jmeter/*`：读基线、Task Workflow、PowerShell non-GUI 运行器和说明。
- `[NEW] docs/performance/16-backend-baseline.md`：环境、结果或 `NOT RUN`、Metrics 联动。
- `[NEW] docs/checklists/16-backend-freeze-checklist.md`：Build/DB/Security/Reliability/Observability/Performance/Docs 验收。
- `[NEW] docs/learning/16-backend-engineering-closure.md`：传统后端能力总结与暂缓边界。
- `[MOD] README.md`、`docs/architecture.md`、`docs/database-design.md`、`docs/capability-coverage-and-roadmap.md`：同步真实阶段与下一步。
- `[MOD] .gitignore`：允许第 16 节学习文档并忽略本地 JMeter 结果。
- `[DEL]`：无。

## ArchUnit 扫描和规则

扫描 `com.obdeadsoup.devpilot..` 的生产 class，排除 test class。规则覆盖：业务模块 slices 无环；Framework 与已批准的 Maven 方向；Controller→Mapper、Domain→Web/Persistence、Persistence→API 禁止；其他模块→目标模块 Mapper/Entity/Repository 实现禁止。

当前规则允许 Application→本模块 Persistence，并允许 boot 组装全部模块。跨模块公开边界以 application service、api/port 和中立 DTO 为主。

## GitHub Actions

Workflow 使用最小 `contents: read`，JDK 21 + Maven cache，运行 `mvn -B -ntp clean verify` 和 `docker compose config`。Compose 所需值是该校验步骤的临时非生产占位值；不读取或上传 `.env`。失败时只上传 Surefire/Failsafe 报告。

## JMeter 与 Metrics

读场景覆盖五个稳定 GET；Task 场景每线程独立 create → plan → start → submit-for-review。所有请求保留鉴权并断言 200，Token 仅来自 `ACCESS_TOKEN`。JMeter 不进入普通 PR Gate。

分析时把 JMeter 分位延迟/错误率与 `http.server.requests`、`jvm.*`、`hikaricp.*`、Outbox/GitHub backlog、oldest ready age 和 open DEAD 对齐。

## 关键 Diff

1. POM 引入测试期 ArchUnit；三组规则在完整 boot classpath 执行。
2. ArchUnit 首次运行发现 Task→Identity/Project Persistence 直连，现已改为 Application Service 和 Port/DTO，未使用规则豁免。
3. CI 将本地正确性/架构检查变成 PR 与 main push 的自动门禁。
4. JMeter 参数化脚本不保存 credential，并把写场景的 Task ID/version 隔离到线程自己的响应链。
5. Freeze Checklist 区分已验证、未验证和 `NOT RUN`，性能数据不虚构。

## 推荐阅读顺序

1. 三个 ArchUnit tests
2. `.github/workflows/backend-ci.yml`
3. `docs/testing/16-backend-test-matrix.md`
4. `performance/jmeter/README.md` 与两个 JMX
5. `docs/performance/16-backend-baseline.md`
6. `docs/checklists/16-backend-freeze-checklist.md`
7. `docs/capability-coverage-and-roadmap.md`

只想理解设计时，可跳过 JMX XML、CI YAML 细节和测试样板。

## 未实现边界

没有新增 MQ、CDC、分布式 SSE、OpenTelemetry、Grafana/Alertmanager、GitHub App、邮件、微服务、Kubernetes 或 Agent。CI 不是 CD；性能计划不是生产容量声明。下一步是 Frontend/E2E alignment，再进入 Knowledge/Agent L1。
