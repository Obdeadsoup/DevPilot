# [EVAL-V2] 10-evaluation-strategy

## Deterministic Evidence

路由、工具名称、合法参数范围、来源文件与 chunk 身份、Memory 写入事实和控制流可用确定性指标。回答正确性及 groundedness 需抽样 Judge 与人工核查。

## Holdout Discipline

Dev 集可用于少量变量的实验；Holdout 用于最终结论，避免按失败题反复定向改 Prompt。所有报告记录 dataset hash、fixture version 与运行环境。
