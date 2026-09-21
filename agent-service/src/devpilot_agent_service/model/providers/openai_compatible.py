"""OpenAI-compatible Chat Completions SDK 与内部模型之间的薄 Adapter。"""

import json
import logging
import re
from collections.abc import Mapping, Sequence
from typing import Protocol

import httpx2
from openai import (
    APIConnectionError,
    APIError,
    APIStatusError,
    APITimeoutError,
    AuthenticationError,
    InternalServerError,
    OpenAI,
    RateLimitError,
)

from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.providers.config import OpenAICompatibleConfig
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.message import Message, MessageRole
from devpilot_agent_service.tools.base import ToolDefinition

LOGGER = logging.getLogger(__name__)


class _CompletionsClient(Protocol):
    def create(self, **request: object) -> object: ...


class _ChatClient(Protocol):
    completions: _CompletionsClient


class _OpenAICompatibleClient(Protocol):
    chat: _ChatClient


def _provider_tool_name(name: str) -> str:
    # DeepSeek function names accept only letters, digits, underscores and dashes.
    alias = re.sub(r"[^A-Za-z0-9_-]", "_", name)
    if not alias or len(alias) > 128:
        raise ProviderError(ProviderErrorKind.PROTOCOL)
    return alias


def to_provider_message(message: Message) -> dict[str, object]:
    """把内部 Message 转成 Chat Completions message，不向 Runtime 泄漏 SDK DTO。"""

    if message.role is MessageRole.ASSISTANT and message.tool_calls:
        provider_message = {
            "role": "assistant",
            "content": message.content,
            "tool_calls": [
                {
                    "id": call.call_id,
                    "type": "function",
                    "function": {
                        "name": _provider_tool_name(call.name),
                        # 稳定编码使同一内部参数得到可复现的 Provider payload。
                        "arguments": json.dumps(
                            dict(call.arguments),
                            ensure_ascii=False,
                            sort_keys=True,
                            separators=(",", ":"),
                            allow_nan=False,
                        ),
                    },
                }
                for call in message.tool_calls
            ],
        }
        if message.reasoning_content is not None:
            provider_message["reasoning_content"] = message.reasoning_content
        return provider_message
    if message.role is MessageRole.TOOL:
        return {
            "role": "tool",
            "content": message.content,
            # tool_call_id 是 Assistant 请求与 Observation 的唯一关联，不得用工具名代替。
            "tool_call_id": message.tool_call_id,
        }
    provider_message = {"role": message.role.value, "content": message.content}
    if message.role is MessageRole.ASSISTANT and message.reasoning_content is not None:
        provider_message["reasoning_content"] = message.reasoning_content
    return provider_message


def to_provider_tool(tool: ToolDefinition) -> dict[str, object]:
    """将内部 ToolDefinition 映射为 OpenAI-compatible function schema。"""

    parameters = _to_plain_value(tool.parameter_schema)
    try:
        json.dumps(parameters, ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError) as error:
        raise ProviderError(ProviderErrorKind.PROTOCOL) from error
    return {
        "type": "function",
        "function": {
            "name": _provider_tool_name(tool.name),
            "description": tool.description,
            "parameters": parameters,
        },
    }


class OpenAICompatibleModel:
    """实现 Model Protocol；默认 Client 指向 DeepSeek 配置的 OpenAI-compatible endpoint。"""

    def __init__(
        self,
        config: OpenAICompatibleConfig,
        *,
        client: _OpenAICompatibleClient | None = None,
    ) -> None:
        self._config = config
        # SDK 默认会自动重试；本章显式关闭，避免一次 generate 隐含重复计费或重复 ToolCall。
        self._client = (
            client
            if client is not None
            else OpenAI(
                api_key=config.api_key,
                base_url=config.base_url,
                max_retries=0,
                timeout=httpx2.Timeout(
                    timeout=config.overall_timeout_seconds,
                    connect=config.connect_timeout_seconds,
                    read=config.read_timeout_seconds,
                ),
            )
        )

    def generate(
        self,
        messages: Sequence[Message],
        tools: Sequence[ToolDefinition],
    ) -> ModelResponse:
        """完成一次无 Retry 的模型调用，并把所有 Provider/协议失败收敛为脱敏错误。"""

        try:
            aliases = {_provider_tool_name(tool.name): tool.name for tool in tools}
            if len(aliases) != len(tools):
                raise ProviderError(ProviderErrorKind.PROTOCOL)
            request: dict[str, object] = {
                "model": self._config.model,
                "messages": [to_provider_message(message) for message in messages],
            }
            if tools:
                request["tools"] = [to_provider_tool(tool) for tool in tools]
        except ProviderError:
            raise
        except (TypeError, ValueError) as error:
            raise ProviderError(ProviderErrorKind.PROTOCOL) from error

        try:
            response = self._client.chat.completions.create(**request)
        except APIError as error:
            if isinstance(error, APIStatusError):
                detail = error.body if isinstance(error.body, dict) else {}
                nested = detail.get("error") if isinstance(detail.get("error"), dict) else detail
                message = nested.get("message") if isinstance(nested, dict) else None
                flags = [term for term in (
                    "reasoning_content", "tool_call_id", "tool_calls", "content",
                    "messages", "token", "schema", "model", "parameter",
                ) if isinstance(message, str) and term in message.lower()]
                safe_field = lambda value: (
                    re.sub(r"[^A-Za-z0-9_-]", "", str(value))[:64]
                    if value is not None else ""
                )
                LOGGER.warning(
                    "Provider rejected request status=%s errorType=%s errorCode=%s "
                    "parameter=%s messageFlags=%s assistantToolTurns=%s reasoningTurns=%s",
                    error.status_code,
                    safe_field(nested.get("type") if isinstance(nested, dict) else None),
                    safe_field(nested.get("code") if isinstance(nested, dict) else None),
                    safe_field(nested.get("param") if isinstance(nested, dict) else None),
                    ",".join(flags),
                    sum(message.role is MessageRole.ASSISTANT and bool(message.tool_calls)
                        for message in messages),
                    sum(message.role is MessageRole.ASSISTANT
                        and message.reasoning_content is not None for message in messages),
                )
            raise ProviderError(_classify_sdk_error(error)) from error
        except Exception as error:
            raise ProviderError(ProviderErrorKind.UNKNOWN) from error

        # Provider response 属于不可信边界；任何缺字段或非法 arguments 都稳定归为 PROTOCOL。
        try:
            return _to_model_response(response, aliases)
        except ProviderError:
            raise
        except Exception as error:
            raise ProviderError(ProviderErrorKind.PROTOCOL) from error


def _to_model_response(response: object, aliases: Mapping[str, str] | None = None) -> ModelResponse:
    choices = getattr(response, "choices", None)
    if not isinstance(choices, Sequence) or isinstance(choices, (str, bytes)) or not choices:
        raise ProviderError(ProviderErrorKind.PROTOCOL)

    message = getattr(choices[0], "message", None)
    if message is None:
        raise ProviderError(ProviderErrorKind.PROTOCOL)
    reasoning_content = getattr(message, "reasoning_content", None)
    if reasoning_content is not None and not isinstance(reasoning_content, str):
        raise ProviderError(ProviderErrorKind.PROTOCOL)
    provider_calls = getattr(message, "tool_calls", None)
    if provider_calls:
        if not isinstance(provider_calls, Sequence) or isinstance(provider_calls, (str, bytes)):
            raise ProviderError(ProviderErrorKind.PROTOCOL)
        content = getattr(message, "content", None)
        if content is not None and not isinstance(content, str):
            raise ProviderError(ProviderErrorKind.PROTOCOL)
        return ModelResponse.request_tools(
            [_to_internal_tool_call(call, aliases or {}) for call in provider_calls],
            content=content or "",
            reasoning_content=reasoning_content,
        )

    content = getattr(message, "content", None)
    if not isinstance(content, str):
        raise ProviderError(ProviderErrorKind.PROTOCOL)
    return ModelResponse.final(content, reasoning_content=reasoning_content)


def _to_internal_tool_call(provider_call: object, aliases: Mapping[str, str]) -> ToolCall:
    call_id = getattr(provider_call, "id", None)
    function = getattr(provider_call, "function", None)
    name = getattr(function, "name", None) if function is not None else None
    raw_arguments = getattr(function, "arguments", None) if function is not None else None
    if not isinstance(call_id, str) or not call_id.strip():
        raise ProviderError(ProviderErrorKind.PROTOCOL)
    if not isinstance(name, str) or not name.strip():
        raise ProviderError(ProviderErrorKind.PROTOCOL)
    if not isinstance(raw_arguments, str):
        raise ProviderError(ProviderErrorKind.PROTOCOL)

    try:
        arguments = json.loads(raw_arguments, parse_constant=_reject_json_constant)
    except (json.JSONDecodeError, ValueError) as error:
        raise ProviderError(ProviderErrorKind.PROTOCOL) from error
    if not isinstance(arguments, dict):
        raise ProviderError(ProviderErrorKind.PROTOCOL)
    return ToolCall(call_id=call_id, name=aliases.get(name, name), arguments=arguments)


def _reject_json_constant(value: str) -> object:
    # Python json 默认接受 NaN/Infinity，但它们不属于标准 JSON，不能进入 Tool 参数。
    raise ValueError(f"non-standard JSON constant: {value}")


def _classify_sdk_error(error: APIError) -> ProviderErrorKind:
    # 顺序很重要：APITimeoutError 是 APIConnectionError 的子类。
    if isinstance(error, AuthenticationError):
        return ProviderErrorKind.AUTH
    if isinstance(error, RateLimitError):
        return ProviderErrorKind.RATE_LIMIT
    if isinstance(error, APITimeoutError):
        return ProviderErrorKind.TIMEOUT
    if isinstance(error, (APIConnectionError, InternalServerError)):
        return ProviderErrorKind.UNAVAILABLE
    if isinstance(error, APIStatusError) and error.status_code >= 500:
        return ProviderErrorKind.UNAVAILABLE
    return ProviderErrorKind.UNKNOWN


def _to_plain_value(value: object) -> object:
    if isinstance(value, Mapping):
        return {str(key): _to_plain_value(item) for key, item in value.items()}
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        return [_to_plain_value(item) for item in value]
    return value
