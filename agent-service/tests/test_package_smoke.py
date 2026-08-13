from devpilot_agent_service import ServiceIdentity


def test_package_import_and_identity_defaults() -> None:
    identity = ServiceIdentity()

    assert identity.name == "devpilot-agent-service"
    assert identity.contract_version == "agent.v1"
