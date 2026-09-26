# [EVAL-V2] 08-deployment-runbook

## TEI Health and Ingestion

Knowledge ingestion 依赖对象存储和 TEI embedding；FAILED 会记录 failureCode，retry 需 expectedVersion。文档只有 READY 且 chunk 可检索才满足评测前置条件。

## Full Stack Startup

真实评测需要 Java Core、Python Agent、MySQL、Redis、对象存储、Embedding、Reranker 与 Tool Gateway。FAKE 模式仅测试评测器自身。
