import json

import pytest
from fakes.fake_model import FakeModel
from test_rpc_servicer import FakeServicerContext

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.rpc.application import AgentRuntimeApplication
from devpilot_agent_service.rpc.generated import agent_runtime_pb2 as pb
from devpilot_agent_service.rpc.servicer import AgentRuntimeServicer
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import ApprovalRequired, ResumeRejected
from devpilot_agent_service.runtime.persistence import RunStatus
from devpilot_agent_service.tools.base import ToolProposal, ToolProposalResolution, ToolRisk
from devpilot_agent_service.tools.registry import ToolRegistry

CONTEXT = RunContext("run", "request")


class ProposalTool:
    name = "task.create"
    description = "create task with approval"
    parameter_schema = {"type": "object", "required": ["title"]}
    risk = ToolRisk.WRITE_REQUIRES_APPROVAL

    def __init__(self):
        self.proposed = []
        self.executed = []
        self.resolution = None

    def execute(self, arguments, **kwargs):
        self.executed.append(arguments)
        raise AssertionError("write tool must not execute directly")

    def create_proposal(self, arguments, *, run_context, tool_call_id):
        self.proposed.append((dict(arguments), run_context, tool_call_id))
        return ToolProposal("proposal-1", tool_call_id, self.name, "PENDING_APPROVAL", "2030-01-01")

    def get_proposal_resolution(self, *, run_context, proposal_id):
        assert run_context == CONTEXT and proposal_id == "proposal-1"
        return self.resolution


class ReadTool:
    name = "read"
    description = "read"
    parameter_schema = {"type": "object"}
    risk = ToolRisk.READ_ONLY

    def __init__(self):
        self.calls = 0

    def execute(self, arguments, **kwargs):
        self.calls += 1
        return {"ok": True}


def registry(*tools):
    result = ToolRegistry()
    for tool in tools:
        result.register(tool)
    return result


def write_call():
    return ModelResponse.request_tools(
        [ToolCall("call-1", "task.create", {"title": "Exact title"})]
    )


def test_read_tool_executes_but_write_tool_creates_durable_interrupt(repository):
    read = ReadTool()
    loop = AgentLoop(
        FakeModel([ModelResponse.request_tools([ToolCall("read-1", "read", {})]), write_call()]),
        registry(read, proposal := ProposalTool()), repository=repository,
    )
    with pytest.raises(ApprovalRequired) as interrupted:
        loop.run("create it", run_context=CONTEXT)
    assert read.calls == 1
    assert proposal.executed == []
    assert proposal.proposed[0][0] == {"title": "Exact title"}
    assert interrupted.value.proposal.proposal_id == "proposal-1"
    run = repository.get_run("run")
    state = repository.get_latest_checkpoint("run").state
    assert run.status is state.status is RunStatus.WAITING_APPROVAL
    assert state.next_action == "WAIT_APPROVAL"
    assert state.pending_proposal.proposal_id == "proposal-1"
    assert state.pending_tool_calls[0].arguments == {"title": "Exact title"}


def test_approved_resume_uses_resolution_and_never_regenerates_arguments(repository):
    proposal = ProposalTool()
    first = AgentLoop(FakeModel([write_call()]), registry(proposal), repository=repository)
    with pytest.raises(ApprovalRequired):
        first.run("original", run_context=CONTEXT)
    proposal.resolution = ToolProposalResolution(
        "proposal-1", "call-1", "task.create", "EXECUTED",
        {"created": True, "resourceId": "42"},
    )
    resumed_model = FakeModel([ModelResponse.final("created")])
    result = AgentLoop(resumed_model, registry(proposal), repository=repository).execute_prepared(
        AgentLoop(resumed_model, registry(proposal), repository=repository)
        .prepare_approval_resume(CONTEXT, "proposal-1")
    )
    assert result.final_answer == "created"
    assert proposal.executed == []
    tool_message = resumed_model.calls[0].messages[-1]
    assert json.loads(tool_message.content) == {"created": True, "resourceId": "42"}
    assert repository.get_run("run").status is RunStatus.SUCCEEDED


@pytest.mark.parametrize("status", ["REJECTED", "EXPIRED", "FAILED"])
def test_nonexecuted_resolution_becomes_tool_result_and_run_continues(repository, status):
    proposal = ProposalTool()
    with pytest.raises(ApprovalRequired):
        AgentLoop(FakeModel([write_call()]), registry(proposal), repository=repository).run(
            "original", run_context=CONTEXT
        )
    proposal.resolution = ToolProposalResolution(
        "proposal-1", "call-1", "task.create", status, {}
    )
    model = FakeModel([ModelResponse.final("handled")])
    loop = AgentLoop(model, registry(proposal), repository=repository)
    loop.execute_prepared(loop.prepare_approval_resume(CONTEXT, "proposal-1"))
    assert json.loads(model.calls[0].messages[-1].content)["status"] == status


def test_pending_or_mismatched_proposal_cannot_resume(repository):
    proposal = ProposalTool()
    with pytest.raises(ApprovalRequired):
        AgentLoop(FakeModel([write_call()]), registry(proposal), repository=repository).run(
            "original", run_context=CONTEXT
        )
    loop = AgentLoop(FakeModel([]), registry(proposal), repository=repository)
    with pytest.raises(ResumeRejected, match="PROPOSAL_MISMATCH"):
        loop.prepare_approval_resume(CONTEXT, "other")
    proposal.resolution = ToolProposalResolution(
        "proposal-1", "call-1", "task.create", "PENDING_APPROVAL", {}
    )
    with pytest.raises(ResumeRejected, match="PROPOSAL_NOT_RESOLVED"):
        loop.prepare_approval_resume(CONTEXT, "proposal-1")


def test_rpc_wait_event_releases_worker_and_resume_stream_starts_with_resumed(repository):
    proposal = ProposalTool()
    model = FakeModel([write_call(), ModelResponse.final("done")])
    servicer = AgentRuntimeServicer(
        AgentRuntimeApplication(AgentLoop(model, registry(proposal), repository=repository))
    )
    request = pb.StreamRunRequest(run_id="run", request_id="request", user_input="original")
    waiting = list(servicer.StreamRun(request, FakeServicerContext()))
    assert waiting[-1].type == pb.AGENT_EVENT_TYPE_RUN_WAITING_APPROVAL
    assert waiting[-1].proposal_id == "proposal-1"
    assert repository.get_run("run").status is RunStatus.WAITING_APPROVAL
    proposal.resolution = ToolProposalResolution(
        "proposal-1", "call-1", "task.create", "REJECTED", {}
    )
    resumed = list(servicer.ResumeApproval(
        pb.ResumeApprovalRequest(run_id="run", request_id="request", proposal_id="proposal-1"),
        FakeServicerContext(),
    ))
    assert resumed[0].type == pb.AGENT_EVENT_TYPE_RUN_RESUMED
    assert resumed[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED
