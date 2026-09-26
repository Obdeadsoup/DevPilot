# [EVAL-V2] 12-deprecated-design

## Status: DEPRECATED

Python 直接读写 Java 业务表、未批准执行 task.create、SSE 断线自动 CancelRun 都是废弃设想，不是现行产品能力。

## Current Replacement

Java 应用服务与 RBAC 是业务边界；写工具先 Proposal；取消必须显式请求。历史方案只供负面对照，不能作为实时项目事实。
