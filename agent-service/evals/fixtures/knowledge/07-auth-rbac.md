# [EVAL-V2] 07-auth-rbac

## Tool Gateway Authorization

Python 调 Java Tool Gateway 时使用内部 service key；Java 从持久 Run 恢复 actor、workspace、project，并在每次 Tool 调用重新验证 RBAC。

## Knowledge Access

Knowledge 上传需要 KNOWLEDGE_MANAGE，列表和检索需要读取权限。文档属于 Project scope，评测不得跨 scope 借用检索结果。
