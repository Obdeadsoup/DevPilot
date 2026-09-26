# [EVAL-V2] 01-system-architecture

## System Ownership Boundary

DevPilot 是模块化 Java Core 加独立 Python Agent 服务。Java 拥有 RBAC、事务、Task 与 AgentRun 权威状态；Python 负责模型编排，通过 gRPC 交互，不直连 dp_* 业务表。

## Project State Sources

Project Summary 从 Project 应用服务读取；Recent Activity 来自 Task 和 GitHub 的真实业务活动，Task 事件在原写事务中记录。
