"""Offline four-route workflow demo; fake business facts do not prove real Java RBAC."""

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from devpilot_agent_service.harness.demo import DemoGatewayClient  # noqa: E402
from devpilot_agent_service.harness.workflow import WorkflowRuntime  # noqa: E402
from devpilot_agent_service.harness.workflow_fake import DeterministicWorkflowModel  # noqa: E402
from devpilot_agent_service.rpc.server import _remote_tool_registry  # noqa: E402
from devpilot_agent_service.runtime.context import RunContext  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--query", default="结合开放任务和架构文档分析当前风险")
    args = parser.parse_args()
    client = DemoGatewayClient()
    model = DeterministicWorkflowModel()
    runtime = WorkflowRuntime(model, _remote_tool_registry(client), lambda: model)
    result = runtime.invoke(args.query, run_context=RunContext("demo-run", "demo-request"))
    print(
        json.dumps(
            {
                "notice": "deterministic offline model/gateway; no real Java RBAC or LLM executed",
                **asdict(result),
                "gateway_tool_order": [call[2] for call in client.calls],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
