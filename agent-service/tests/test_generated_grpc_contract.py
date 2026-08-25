from devpilot_agent_service.rpc.generated import agent_runtime_pb2, agent_runtime_pb2_grpc


def test_generated_contract_exposes_v1_start_run() -> None:
    request = agent_runtime_pb2.StartRunRequest(
        request_id="request-1",
        run_id="run-1",
        user_input="hello",
    )

    assert request.request_id == "request-1"
    assert request.run_id == "run-1"
    assert request.user_input == "hello"
    assert "StartRun" in agent_runtime_pb2.DESCRIPTOR.services_by_name[
        "AgentRuntime"
    ].methods_by_name
    assert hasattr(agent_runtime_pb2_grpc, "AgentRuntimeStub")
    assert hasattr(agent_runtime_pb2_grpc, "AgentRuntimeServicer")

