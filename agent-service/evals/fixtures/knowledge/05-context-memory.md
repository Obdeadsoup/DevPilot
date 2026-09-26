# [EVAL-V2] 05-context-memory

## Memory Scope Isolation

长期记忆按 workspace、project、user scope 绑定。相同 scope 的稳定偏好可以召回，跨 Project 或 Workspace 不应召回；敏感凭据不应写入。

## Context Budget

Context Manager 有消息预算、摘要与 Tool 结果截断。截断后仍需保留有效 JSON 的来源标识；不要把原始 Memory 或 RAG 正文写入公共 Safe Trace。
