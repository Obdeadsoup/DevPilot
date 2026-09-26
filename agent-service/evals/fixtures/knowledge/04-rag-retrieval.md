# [EVAL-V2] 04-rag-retrieval

## Retrieval Pipeline

Knowledge 上传后异步 ingestion，解析、按长度与段落边界 chunk、调用 TEI embedding、持久化 chunk。检索结合 dense/sparse 融合和 rerank，返回 sourceFile、chunkId、分数。

## Query Rewrite

检索可利用有限 conversationHistory 改写查询。当前 chunkId 与文件来源可追踪，但 section ID 尚不是服务端稳定字段；Manifest 的 section 标题用于人工证据核对。
