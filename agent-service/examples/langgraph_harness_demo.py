"""Opt-in Harness CLI. Fake is offline; real requires an existing active Java run."""

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from devpilot_agent_service.context import ContextBudget  # noqa: E402
from devpilot_agent_service.harness.demo import (  # noqa: E402
    DEMO_QUERY,
    DemoAnalystModel,
    DemoGatewayClient,
    DemoMainModel,
)
from devpilot_agent_service.harness.runtime import (  # noqa: E402
    HarnessConfig,
    create_remote_harness,
)
from devpilot_agent_service.model.providers.config import OpenAICompatibleConfig  # noqa: E402
from devpilot_agent_service.model.providers.openai_compatible import (  # noqa: E402
    OpenAICompatibleModel,
)
from devpilot_agent_service.rpc.tool_gateway_client import (  # noqa: E402
    JavaToolGatewayClient,
    JavaToolGatewayConfig,
)
from devpilot_agent_service.runtime.context import RunContext  # noqa: E402
from devpilot_agent_service.runtime.redaction import RuntimeRedactor  # noqa: E402


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("fake", "real"), default="fake")
    parser.add_argument("--query", default=DEMO_QUERY)
    parser.add_argument("--run-id")
    parser.add_argument("--request-id")
    parser.add_argument("--max-context-chars", type=int, default=24000)
    args = parser.parse_args(argv)
    if args.mode == "real" and (not args.run_id or not args.request_id):
        parser.error("real mode requires --run-id and --request-id of an active Java run")
    client = None
    try:
        config = HarnessConfig(
            context_budget=ContextBudget(
                max_context_chars=args.max_context_chars,
            )
        )
        if args.mode == "fake":
            client = DemoGatewayClient()
            runtime = create_remote_harness(
                DemoMainModel(), client, DemoAnalystModel, config=config
            )
            context = RunContext("demo-run", "demo-request")
        else:
            provider_config = OpenAICompatibleConfig.from_deepseek_env()
            gateway_config = JavaToolGatewayConfig.from_env()
            client = JavaToolGatewayClient(gateway_config)
            analyst_model = OpenAICompatibleModel(provider_config)
            runtime = create_remote_harness(
                OpenAICompatibleModel(provider_config),
                client,
                lambda: analyst_model,
                config=config,
                redactor=RuntimeRedactor((provider_config.api_key, gateway_config.service_key)),
            )
            context = RunContext(args.run_id, args.request_id)
        result = runtime.invoke(args.query, run_context=context)
        payload = {"mode": args.mode, **asdict(result)}
        if args.mode == "fake":
            payload["gateway_tool_order"] = [call[2] for call in client.calls]
            payload["notice"] = "deterministic offline model/gateway; no real Java RBAC executed"
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0
    except Exception as error:
        # Do not print untrusted exception payloads, prompts, credentials or user data.
        print(f"Harness failed: {type(error).__name__}", file=sys.stderr)
        return 1
    finally:
        if isinstance(client, JavaToolGatewayClient):
            client.close()


if __name__ == "__main__":
    raise SystemExit(main())
