import json
from types import SimpleNamespace

import httpx2
import pytest
from fakes.fake_openai_client import FakeOpenAIClient
from openai import (
    APIConnectionError,
    APITimeoutError,
    AuthenticationError,
    InternalServerError,
    RateLimitError,
)

from devpilot_agent_service.model.errors import (
    ProviderConfigurationError,
    ProviderError,
    ProviderErrorKind,
)
from devpilot_agent_service.model.providers import openai_compatible
from devpilot_agent_service.model.providers.config import (
    DEFAULT_DEEPSEEK_BASE_URL,
    DEFAULT_DEEPSEEK_MODEL,
    OpenAICompatibleConfig,
)
from devpilot_agent_service.model.providers.openai_compatible import (
    OpenAICompatibleModel,
    to_provider_message,
    to_provider_tool,
)
from devpilot_agent_service.model.types import ModelResponseKind, ToolCall
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.tools.echo import EchoTool
from devpilot_agent_service.tools.registry import ToolRegistry


def completion(*, content: object = None, tool_calls: object = None) -> object:
    message = SimpleNamespace(content=content, tool_calls=tool_calls)
    return SimpleNamespace(choices=[SimpleNamespace(message=message)])


def provider_call(
    call_id: object = "call-1",
    name: object = "echo",
    arguments: object = '{"text":"hello"}',
) -> object:
    return SimpleNamespace(
        id=call_id,
        function=SimpleNamespace(name=name, arguments=arguments),
    )


def model_with(result: object) -> tuple[OpenAICompatibleModel, FakeOpenAIClient]:
    client = FakeOpenAIClient(result)
    config = OpenAICompatibleConfig(api_key="test-secret")
    return OpenAICompatibleModel(config, client=client), client


def echo_definitions() -> tuple[object, ...]:
    registry = ToolRegistry()
    registry.register(EchoTool())
    return registry.definitions()


def test_deepseek_environment_defaults_and_overrides() -> None:
    default = OpenAICompatibleConfig.from_deepseek_env({"DEEPSEEK_API_KEY": "secret"})
    overridden = OpenAICompatibleConfig.from_deepseek_env(
        {
            "DEEPSEEK_API_KEY": "secret",
            "DEEPSEEK_BASE_URL": "https://provider.example/v1",
            "DEEPSEEK_MODEL": "custom-model",
        }
    )

    assert default.base_url == DEFAULT_DEEPSEEK_BASE_URL
    assert default.model == DEFAULT_DEEPSEEK_MODEL
    assert overridden.base_url == "https://provider.example/v1"
    assert overridden.model == "custom-model"
    assert "secret" not in repr(default)


def test_missing_api_key_is_rejected_without_secret_value() -> None:
    with pytest.raises(ProviderConfigurationError, match="DEEPSEEK_API_KEY") as captured:
        OpenAICompatibleConfig.from_deepseek_env({})

    assert "secret" not in str(captured.value).lower()


def test_system_user_and_plain_assistant_message_mapping() -> None:
    assert to_provider_message(Message.system("rules")) == {
        "role": "system",
        "content": "rules",
    }
    assert to_provider_message(Message.user("question")) == {
        "role": "user",
        "content": "question",
    }
    assert to_provider_message(Message.assistant("answer")) == {
        "role": "assistant",
        "content": "answer",
    }


def test_assistant_tool_call_and_tool_result_mapping_preserve_call_id() -> None:
    call = ToolCall("call-7", "echo", {"z": 1, "a": "值"})
    assistant = to_provider_message(Message.assistant_tool_calls([call]))
    tool_result = to_provider_message(Message.tool_result(call, '{"echo":"值"}'))

    assert assistant == {
        "role": "assistant",
        "content": None,
        "tool_calls": [
            {
                "id": "call-7",
                "type": "function",
                "function": {
                    "name": "echo",
                    "arguments": '{"a":"值","z":1}',
                },
            }
        ],
    }
    assert tool_result == {
        "role": "tool",
        "content": '{"echo":"值"}',
        "tool_call_id": "call-7",
    }


def test_tool_definition_mapping_uses_function_schema() -> None:
    mapped = to_provider_tool(echo_definitions()[0])

    assert mapped["type"] == "function"
    assert mapped["function"]["name"] == "echo"  # type: ignore[index]
    assert mapped["function"]["parameters"]["required"] == ["text"]  # type: ignore[index]


def test_generate_maps_request_and_normalizes_final_response() -> None:
    model, client = model_with(completion(content="完成", tool_calls=None))

    response = model.generate([Message.user("开始")], echo_definitions())

    assert response.kind is ModelResponseKind.FINAL
    assert response.content == "完成"
    request = client.chat.completions.requests[0]
    assert request["model"] == DEFAULT_DEEPSEEK_MODEL
    assert request["messages"] == [{"role": "user", "content": "开始"}]
    assert request["tools"][0]["function"]["name"] == "echo"  # type: ignore[index]


def test_generate_normalizes_multiple_tool_calls() -> None:
    model, _ = model_with(
        completion(
            content="",
            tool_calls=[
                provider_call("call-1", arguments='{"text":"一"}'),
                provider_call("call-2", arguments='{"text":"二"}'),
            ],
        )
    )

    response = model.generate([Message.user("两次")], echo_definitions())

    assert response.kind is ModelResponseKind.TOOL_CALLS
    assert [call.call_id for call in response.tool_calls] == ["call-1", "call-2"]
    assert [dict(call.arguments) for call in response.tool_calls] == [
        {"text": "一"},
        {"text": "二"},
    ]


@pytest.mark.parametrize(
    "arguments",
    ["{", "[]", '"text"', "null", '{"value":NaN}'],
)
def test_invalid_or_non_object_tool_arguments_are_protocol_error(arguments: str) -> None:
    model, _ = model_with(completion(tool_calls=[provider_call(arguments=arguments)]))

    with pytest.raises(ProviderError) as captured:
        model.generate([Message.user("go")], echo_definitions())

    assert captured.value.kind is ProviderErrorKind.PROTOCOL


@pytest.mark.parametrize(
    ("call_id", "name"),
    [(None, "echo"), ("", "echo"), ("call-1", None), ("call-1", " ")],
)
def test_missing_tool_identity_is_protocol_error(call_id: object, name: object) -> None:
    model, _ = model_with(
        completion(tool_calls=[provider_call(call_id=call_id, name=name)])
    )

    with pytest.raises(ProviderError) as captured:
        model.generate([Message.user("go")], echo_definitions())

    assert captured.value.kind is ProviderErrorKind.PROTOCOL


def sdk_errors() -> list[tuple[Exception, ProviderErrorKind]]:
    request = httpx2.Request("POST", "https://provider.example/chat/completions")
    unauthorized = httpx2.Response(401, request=request)
    rate_limited = httpx2.Response(429, request=request)
    server_error = httpx2.Response(503, request=request)
    return [
        (
            AuthenticationError("unauthorized", response=unauthorized, body=None),
            ProviderErrorKind.AUTH,
        ),
        (
            RateLimitError("slow down", response=rate_limited, body=None),
            ProviderErrorKind.RATE_LIMIT,
        ),
        (APITimeoutError(request=request), ProviderErrorKind.TIMEOUT),
        (APIConnectionError(request=request), ProviderErrorKind.UNAVAILABLE),
        (
            InternalServerError("down", response=server_error, body=None),
            ProviderErrorKind.UNAVAILABLE,
        ),
    ]


@pytest.mark.parametrize(("sdk_error", "expected"), sdk_errors())
def test_real_sdk_exception_types_map_to_stable_kinds(
    sdk_error: Exception,
    expected: ProviderErrorKind,
) -> None:
    model, _ = model_with(sdk_error)

    with pytest.raises(ProviderError) as captured:
        model.generate([Message.user("go")], echo_definitions())

    assert captured.value.kind is expected
    assert str(captured.value) == f"provider invocation failed: {expected.value}"
    assert captured.value.__cause__ is sdk_error


def test_unknown_client_exception_is_sanitized() -> None:
    model, _ = model_with(RuntimeError("token=must-not-leak"))

    with pytest.raises(ProviderError) as captured:
        model.generate([Message.user("go")], echo_definitions())

    assert captured.value.kind is ProviderErrorKind.UNKNOWN
    assert "must-not-leak" not in str(captured.value)


def test_default_sdk_client_disables_implicit_retries(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}
    client = FakeOpenAIClient(completion(content="done"))

    def create_client(**options: object) -> FakeOpenAIClient:
        captured.update(options)
        return client

    monkeypatch.setattr(openai_compatible, "OpenAI", create_client)

    OpenAICompatibleModel(OpenAICompatibleConfig(api_key="test-secret"))

    assert captured == {
        "api_key": "test-secret",
        "base_url": DEFAULT_DEEPSEEK_BASE_URL,
        "max_retries": 0,
        "timeout": captured["timeout"],
    }
    timeout = captured["timeout"]
    assert timeout.connect == 2.0
    assert timeout.read == 30.0


def test_provider_payload_is_json_serializable() -> None:
    payload = to_provider_tool(echo_definitions()[0])

    assert json.loads(json.dumps(payload))["function"]["name"] == "echo"
