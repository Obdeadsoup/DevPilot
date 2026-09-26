# DevPilot Agent Eval V2 测评结果

## 1. 测评环境

- 模式：REAL
- Dataset：`devpilot_e2e_v2_dev.jsonl`
- Dataset Version：V2
- Fixture Version：`devpilot-eval-v2`
- Dev Case 数量：60
- Generation Model：`deepseek-flash`
- Planner Model：`deepseek-flash`
- RAG TopK：5
- Judge：还没搞
- Workspace ID：1
- Project ID：6

### Fixture 数据

- Task：28 条
- Knowledge Document：12 篇
- Knowledge READY：12 / 12
- Knowledge FAILED：0
- Open Task 额外污染：0
- Knowledge Smoke Test：3 / 3 通过

## 2. Case 执行结果

- Cases Total：60
- Cases Evaluated：57
- Cases Succeeded：54
- Cases Failed：3
- Model Error：1
- Timeout：0
- Skipped：3
  - Environment：1
  - Unsupported：2
- Scenario Run Success：94.74%

> 注：部分 AgentRun 技术上执行成功，但未满足对应 Eval Scenario 的预期行为，因此 scenario success 与底层 Run 终态不能简单等同。

## 3. Planner / Routing

- Route Accuracy：**88.24%**
- Observed：51
- Planner Fallback Rate：8.93%
- Planner Error Rate：8.93%
- Low Confidence Rate：8.93%

### Route Confusion Matrix

| Expected | Correct | Misroute |
|---|---:|---|
| ONLY_TOOL | 13 | 2 → HYBRID，1 → ONLY_RAG |
| DIRECT | 8 | 1 → HYBRID |
| ONLY_RAG | 11 | 1 → HYBRID，1 → DIRECT |
| HYBRID | 13 | 0 |

### 关键现象

- HYBRID：13 / 13 正确。
- 当前误判主要集中在 DIRECT / ONLY_TOOL / ONLY_RAG 的边界。
- 部分误判来源于 Provider Error 后 fallback 到 HYBRID，而非纯语义判断错误。

## 4. Tool Calling

- Tool Exact Match：**53.57%**
- Tool Precision：**70.15%**
- Tool Recall：**94.64%**
- Tool Argument Accuracy：**97.44%**

### Bad Case 分析

- Tool Exact Match 失败：26 个 Case
- 其中约 23 个：
  - 正确 Tool 已经调用
  - 但额外调用了不必要 Tool
- 只有少量属于真正漏掉必要 Tool

### 当前主要问题

当前 Tool 侧主要不是“不会选 Tool”，而是：

**Tool Over-Calling / Tool Fan-out 过大。**

常见现象：

```text
本来只需要：
task.list_open

实际调用：
task.list_open
+ project.get_summary
+ project.list_recent_activity
```

下一轮重点：
- 提高 Tool Precision
- 减少不必要 Tool Call
- 保持当前较高 Tool Recall / Argument Accuracy
- 同时观察 Token 和 Latency 是否下降

## 5. RAG

- Source Recall@K：**96.15%**
- Evidence Recall@K：**96.15%**
- Source MRR：**0.7949**
- Evidence MRR：**0.7949**
- Citation Present：**92.31%**
- RAG Empty Result：**0%**

### 关键现象

26 个带 RAG Ground Truth 的 Case 中：

- Rank 1：约 17 个
- Rank 2：约 6 个
- Rank 3：约 2 个
- Miss：1 个

唯一明显 Recall Miss 并非真正 Retrieval Miss：

```text
Expected：ONLY_RAG
Actual：DIRECT
```

因为 Planner 没有进入 RAG，所以没有发生检索。

### 当前判断

- RAG 召回不是当前主要瓶颈。
- 下一阶段比“继续提高 Recall”更值得关注：
  - 排序质量 / MRR
  - Rerank
  - Hybrid Retrieval
  - Chunk 策略实验

## 6. Memory

- Memory Write Correct：100%（5 observed）
- Memory False Write：0%
- Memory False Write Rate：0%
- Memory Recall Hit Rate：53.57%
- Cross-run Recall Success Rate：0%（4 observed）
- Expected Memory Recall Accuracy：0%（5 observed）

### 当前判断

不能直接得出“Memory 失效”。

部分 Case 的最终回答与 Trace 已出现实际 recall 行为，但当前 cross-run 指标和 Memory 生命周期/重复写入/已有 Memory 状态之间可能存在偏差。

下一步：
- 使用独立 Eval Memory Scope
- 确保 Run1 真正产生新 Memory
- 区分：
  - new write
  - existing memory
  - duplicate suppression
  - cross-run recall
- 修正或验证 Eval 判定后再评价 Memory 能力

## 7. Reliability / HITL

当前未得到有效 hard-gate observation：

- `write_without_approval`：Unknown
- `duplicate_side_effect`：Unknown
- `unauthorized_tool_execution`：Unknown
- Hard Gates Pass：Unknown

原因是普通 AgentRun Safe Trace 还不足以完整证明这些可靠性约束，部分 Approval / Expiration 场景需要专门 Fixture Driver。


## 8. Context

- Context Summary Used Rate：5.36%
- Average Context Omitted Messages：0.839
- Average Truncated Tool Result Count：0.054
- Context Budget Failure Rate：当前未观测

本轮 Context 并非主要异常来源

## 9. Latency / 调用量

- Run Latency P50：**15.706 s**
- Run Latency P95：**39.474 s**
- Planner Latency P50：**1.783 s**
- Planner Latency P95：**3.394 s**

平均每个 Run：

- Model Call：3.071
- Tool Call：1.875
- Delegation：0.018
- Memory Recalled：0.679
- Memory Written：0

### 现象

Tool Exact Match 正常的 Case，整体延迟通常低于 Tool Over-Calling 的 Case

因此降低 Tool Fan-out 有机会同时优化：

- Tool Precision
- Token 消耗
- Model Call 次数
- P50 / P95 Latency

## 10. 当前 Baseline 结论

### 表现较好的部分

- RAG Recall@K：96.15%
- Tool Recall：94.64%
- Tool Argument Accuracy：97.44%
- Citation Present：92.31%
- HYBRID Routing：13 / 13
- Memory False Write：0%

### 当前主要问题

1. **Tool Over-Calling**
   - Tool Exact Match 只有 53.57%
   - 主要原因是额外调用不必要 Tool，而不是漏 Tool

2. **Planner 边界误判**
   - Route Accuracy 88.24%
   - DIRECT / ONLY_TOOL / ONLY_RAG 之间仍有混淆
   - 一部分来自 Provider Error fallback

3. **Latency 偏高**
   - P50 15.7s
   - P95 39.5s
   - Tool Fan-out 可能是重要因素

4. **Memory Eval 结果存在疑点**
   - 需要先验证 Eval 生命周期与 Memory 隔离，再调 Memory

5. **HITL / Reliability 尚未完整覆盖**
   - Hard Gate 当前保持 Unknown
