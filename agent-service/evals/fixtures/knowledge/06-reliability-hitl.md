# [EVAL-V2] 06-reliability-hitl

## Approval Safety Rules

task.create 是高风险写工具。Agent 只能先创建 Proposal；批准后由 Java 应用服务执行，拒绝和过期不能产生 Task。版本与状态校验保护并发。

## Resume and Cancel

Checkpoint 恢复须验证身份与状态，避免重复副作用。Cancel 是显式持久意图，SSE 断线不能直接视为取消；Run 终态由 Java 投影为准。
