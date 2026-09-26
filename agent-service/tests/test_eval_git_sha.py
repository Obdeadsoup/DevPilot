import subprocess

import pytest

from devpilot_agent_service.eval import cli


def test_git_sha_prefers_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DEVPILOT_EVAL_GIT_SHA", "from-environment")

    def unexpected_git(*args: object, **kwargs: object) -> None:
        pytest.fail("git should not be called when the SHA is provided")

    monkeypatch.setattr(cli.subprocess, "run", unexpected_git)
    assert cli._git_sha() == "from-environment"


def test_git_sha_uses_local_git(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DEVPILOT_EVAL_GIT_SHA", raising=False)
    commands = []

    def successful_git(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
        commands.append((command, kwargs))
        return subprocess.CompletedProcess(command, 0, stdout="from-git\n")

    monkeypatch.setattr(cli.subprocess, "run", successful_git)
    assert cli._git_sha() == "from-git"
    assert commands == [
        (["git", "rev-parse", "HEAD"], {"capture_output": True, "text": True, "check": False})
    ]


def test_git_sha_unavailable_when_git_is_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DEVPILOT_EVAL_GIT_SHA", raising=False)

    def missing_git(*args: object, **kwargs: object) -> None:
        raise FileNotFoundError("git")

    monkeypatch.setattr(cli.subprocess, "run", missing_git)
    assert cli._git_sha() == "unavailable"


def test_git_sha_unavailable_when_git_fails(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DEVPILOT_EVAL_GIT_SHA", raising=False)
    monkeypatch.setattr(
        cli.subprocess,
        "run",
        lambda command, **kwargs: subprocess.CompletedProcess(command, 1, stdout=""),
    )
    assert cli._git_sha() == "unavailable"


def test_git_sha_unavailable_on_other_os_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DEVPILOT_EVAL_GIT_SHA", raising=False)

    def inaccessible_git(*args: object, **kwargs: object) -> None:
        raise PermissionError("git")

    monkeypatch.setattr(cli.subprocess, "run", inaccessible_git)
    assert cli._git_sha() == "unavailable"
