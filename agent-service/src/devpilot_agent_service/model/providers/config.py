"""OpenAI-compatible Provider 的环境配置。"""

import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Self

from devpilot_agent_service.model.errors import ProviderConfigurationError

DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"


@dataclass(frozen=True, slots=True)
class OpenAICompatibleConfig:
    """保存创建 SDK Client 所需的最小配置；repr 明确排除 API Key。"""

    api_key: str = field(repr=False)
    base_url: str = DEFAULT_DEEPSEEK_BASE_URL
    model: str = DEFAULT_DEEPSEEK_MODEL

    def __post_init__(self) -> None:
        if not isinstance(self.api_key, str) or not self.api_key.strip():
            raise ProviderConfigurationError("DEEPSEEK_API_KEY is required")
        if not isinstance(self.base_url, str) or not self.base_url.strip():
            raise ProviderConfigurationError("DEEPSEEK_BASE_URL must not be blank")
        if not isinstance(self.model, str) or not self.model.strip():
            raise ProviderConfigurationError("DEEPSEEK_MODEL must not be blank")

    @classmethod
    def from_deepseek_env(cls, environ: Mapping[str, str] | None = None) -> Self:
        """只读取约定环境变量，不从文件、日志或命令行拼接 Secret。"""

        source = os.environ if environ is None else environ
        return cls(
            api_key=source.get("DEEPSEEK_API_KEY", ""),
            base_url=source.get("DEEPSEEK_BASE_URL", DEFAULT_DEEPSEEK_BASE_URL),
            model=source.get("DEEPSEEK_MODEL", DEFAULT_DEEPSEEK_MODEL),
        )
