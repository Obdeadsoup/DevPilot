"""持久化专用脱敏；不修改正在传给 Model/Tool 的内存对象。"""

import json
import re
from collections.abc import Mapping, Sequence

REDACTED = "[REDACTED]"
_SECRET_KEY = re.compile(
    r"token|secret|password|passwd|credential|authorization|apikey|servicekey|privatekey|cookie",
    re.IGNORECASE,
)
_LABEL = (
    r"[\w-]*(?:token|secret|password|passwd|credential|authorization|"
    r"api[_ -]?key|service[_ -]?key|private[_ -]?key|cookie)[\w-]*"
)
_ASSIGNMENT = re.compile(rf"""(?i)(\b{_LABEL}["']?\s*[:=]\s*)("[^"\n]*"|'[^'\n]*'|[^\s,;}}]+)""")
_AUTH = re.compile(r"(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+")
_API_KEY = re.compile(r"\bsk-[A-Za-z0-9_-]{8,}\b")
_PRIVATE_KEY = re.compile(
    r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----", re.DOTALL
)


class RuntimeRedactor:
    def __init__(self, known_secrets: Sequence[str] = ()) -> None:
        self._secrets = tuple(
            sorted({value for value in known_secrets if value}, key=len, reverse=True)
        )

    def redact(self, value: object) -> object:
        if isinstance(value, Mapping):
            return {
                key: REDACTED
                if _SECRET_KEY.search(re.sub(r"[^a-zA-Z]", "", key))
                else self.redact(item)
                for key, item in value.items()
            }
        if isinstance(value, (tuple, list)):
            return [self.redact(item) for item in value]
        if not isinstance(value, str):
            return value
        # Tool message.content 是 JSON 字符串；必须递归处理里面的字段，不能只检查外层 key。
        if value.lstrip().startswith(("{", "[")):
            try:
                decoded = json.loads(value)
            except ValueError:
                pass
            else:
                safe = self.redact(decoded)
                if safe != decoded:
                    return json.dumps(safe, ensure_ascii=False, sort_keys=True)
                return value
        for secret in self._secrets:
            value = value.replace(secret, REDACTED)
        value = _PRIVATE_KEY.sub(REDACTED, value)
        value = _AUTH.sub(REDACTED, value)
        value = _API_KEY.sub(REDACTED, value)
        return _ASSIGNMENT.sub(lambda match: match[1] + REDACTED, value)
