"""手工验证 DeepSeek Chat Completions -> Echo Tool -> Final 的最短真实链路。"""

import os
import sys
from pathlib import Path

SOURCE_ROOT = Path(__file__).resolve().parents[1] / "src"
sys.path.insert(0, str(SOURCE_ROOT))

from devpilot_agent_service.config import create_runtime_repository  # noqa: E402
from devpilot_agent_service.model.errors import ProviderErrorKind  # noqa: E402
from devpilot_agent_service.model.providers.config import OpenAICompatibleConfig  # noqa: E402
from devpilot_agent_service.model.providers.openai_compatible import (  # noqa: E402
    OpenAICompatibleModel,
)
from devpilot_agent_service.runtime.agent_loop import AgentLoop  # noqa: E402
from devpilot_agent_service.runtime.errors import (  # noqa: E402
    AgentRuntimeError,
    ModelInvocationError,
)
from devpilot_agent_service.tools.echo import EchoTool  # noqa: E402
from devpilot_agent_service.tools.registry import ToolRegistry  # noqa: E402


def main() -> int:
    if not os.environ.get("DEEPSEEK_API_KEY", "").strip():
        print("NOT RUN: DEEPSEEK_API_KEY is not set")
        return 0

    registry = ToolRegistry()
    registry.register(EchoTool())
    loop = AgentLoop(
        OpenAICompatibleModel(OpenAICompatibleConfig.from_deepseek_env()),
        registry,
        repository=create_runtime_repository(),
        max_steps=4,
        max_tool_calls=2,
        system_prompt=(
            "You are a smoke-test agent. Call the echo tool exactly once with text "
            "'devpilot-smoke', then give a short final answer confirming the returned value."
        ),
    )

    try:
        result = loop.run("Run the requested smoke test now.")
    except ModelInvocationError as error:
        if error.provider_kind in {
            ProviderErrorKind.TIMEOUT,
            ProviderErrorKind.UNAVAILABLE,
        }:
            print(f"BLOCKED: provider_kind={error.provider_kind.value}")
            return 2
        print(f"FAIL: provider_kind={error.provider_kind.value}")
        return 1
    except AgentRuntimeError as error:
        print(f"FAIL: stop_reason={error.stop_reason.value}")
        return 1

    print(
        "PASS: "
        f"stop_reason={result.stop_reason.value}, "
        f"steps={len(result.trace)}, final_answer={result.final_answer}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
