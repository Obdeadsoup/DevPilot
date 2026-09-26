# [EVAL-V2] 02-agent-runtime

## Runtime Ownership Boundary

AgentRun 入口在 Java，Python LangGraph 负责 Planner、Tool、RAG 与 continuation。Java gRPC streaming 到 SSE，Run 身份用于 Tool Gateway 恢复 actor scope。

## Checkpoint Resume Safety

Resume 只接受原 runId/requestId，检查 checkpoint 与可恢复状态；写路径等待 Proposal 批准。SSE 客户端断开不等于显式 CancelRun。
