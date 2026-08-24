"""Provider Adapter 对外暴露的稳定、脱敏失败分类。"""

from enum import StrEnum


class ProviderErrorKind(StrEnum):
    AUTH = "auth"
    RATE_LIMIT = "rate_limit"
    TIMEOUT = "timeout"
    UNAVAILABLE = "unavailable"
    PROTOCOL = "protocol"
    UNKNOWN = "unknown"


class ProviderError(Exception):
    """屏蔽 SDK 异常文本，只把稳定分类交给 Runtime。"""

    def __init__(self, kind: ProviderErrorKind) -> None:
        super().__init__(f"provider invocation failed: {kind.value}")
        self.kind = kind


class ProviderConfigurationError(ValueError):
    """表示环境配置缺失或格式无效，错误文本不得包含 Secret 值。"""
