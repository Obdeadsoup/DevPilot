from concurrent.futures import ThreadPoolExecutor

import grpc
from fakes.fake_model import FakeModel
from google.protobuf.struct_pb2 import Struct
from langchain_core.messages import HumanMessage

from devpilot_agent_service.graph import build_agent_graph
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.rpc.generated import agent_runtime_pb2 as pb
from devpilot_agent_service.rpc.generated import agent_runtime_pb2_grpc as rpc
from devpilot_agent_service.rpc.tool_gateway_client import (
    JavaToolGatewayClient,
    JavaToolGatewayConfig,
)
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.tools.devpilot import KnowledgeSearchTool, ProjectSummaryTool
from devpilot_agent_service.tools.registry import ToolRegistry


def test_graph_uses_existing_client_over_real_grpc_transport_without_changing_security_contract():
    calls = []
    service_key = "test-service-key-123456"

    class Gateway(rpc.DevPilotToolGatewayServicer):
        def ExecuteTool(self, request, context):
            assert (
                dict(context.invocation_metadata())["x-devpilot-agent-service-key"] == service_key
            )
            calls.append(request)
            result = Struct()
            result.update({"external_untrusted_content": True, "sourceFile": "README.md"})
            return pb.ExecuteToolResponse(
                tool_call_id=request.tool_call_id,
                status=pb.TOOL_EXECUTION_STATUS_SUCCEEDED,
                result=result,
            )

    with ThreadPoolExecutor(max_workers=2) as executor:
        server = grpc.server(executor)
        rpc.add_DevPilotToolGatewayServicer_to_server(Gateway(), server)
        port = server.add_insecure_port("127.0.0.1:0")
        server.start()
        target = f"127.0.0.1:{port}"
        channel = grpc.insecure_channel(target, options=(("grpc.enable_http_proxy", 0),))
        grpc.channel_ready_future(channel).result(timeout=5)
        client = JavaToolGatewayClient(
            JavaToolGatewayConfig(
                target=target,
                service_key=service_key,
            ),
            channel=channel,
        )
        try:
            registry = ToolRegistry()
            registry.register(ProjectSummaryTool(client))
            registry.register(KnowledgeSearchTool(client))
            model = FakeModel(
                [
                    ModelResponse.request_tools(
                        [
                            ToolCall("c1", "project.get_summary", {}),
                            ToolCall("c2", "knowledge.search", {"query": "architecture"}),
                        ]
                    ),
                    ModelResponse.final("grounded"),
                ]
            )
            identity = RunContext("run-transport", "request-transport")
            result = build_agent_graph(model, registry).invoke(
                {
                    "messages": [HumanMessage(content="query")],
                    "run_id": identity.run_id,
                    "request_id": identity.request_id,
                    "tool_call_count": 0,
                    "final_answer": None,
                    "stop_reason": None,
                }
            )
            assert result["final_answer"] == "grounded"
            assert [
                (request.run_id, request.request_id, request.tool_call_id) for request in calls
            ] == [
                (identity.run_id, identity.request_id, "c1"),
                (identity.run_id, identity.request_id, "c2"),
            ]
            assert service_key not in str(model.calls)
        finally:
            client.close()
            channel.close()
            server.stop(grace=0).wait()
