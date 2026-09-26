# [EVAL-V2] 09-incident-postmortem

## Embedding Failure Boundary

历史诊断中 TEI 模型下载和容器内存导致 ingestion 失败；上传成功不能代表 READY。排查应先看文档 status/failureCode，再看 TEI health 与日志。

## SSE Disconnect Diagnosis

曾出现 SSE 异步认证与终态连接问题；修复后终态流正常关闭。浏览器断线需核对 Java Run 状态，不能推断 Python Agent 已取消。
