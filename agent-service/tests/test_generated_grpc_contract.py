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


def test_generated_stream_contract_contains_identity_sequence_and_typed_event() -> None:
    request = agent_runtime_pb2.StreamRunRequest(
        run_id="run-1", request_id="request-1", user_input="hello"
    )
    event = agent_runtime_pb2.AgentEvent(
        event_id="run-1:1",
        run_id="run-1",
        sequence=1,
        type=agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED,
    )

    assert request.request_id == "request-1"
    assert request.user_input == "hello"
    assert event.sequence == 1
    assert event.type == agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED
