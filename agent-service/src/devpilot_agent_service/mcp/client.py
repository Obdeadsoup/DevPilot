"""One SDK lifecycle task on a dedicated loop, bridged from synchronous gRPC workers."""

import asyncio
import logging
import threading
import time
from concurrent.futures import Future
from contextlib import asynccontextmanager

import httpx2
from jsonschema import Draft202012Validator
from mcp import Client
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.exceptions import MCPError
from mcp.types import REQUEST_TIMEOUT

from devpilot_agent_service.mcp.catalog import ALLOWLIST, validate_catalog
from devpilot_agent_service.mcp.config import GitHubMcpConfig
from devpilot_agent_service.mcp.errors import GitHubMcpError

LOGGER = logging.getLogger(__name__)


@asynccontextmanager
async def github_sdk_client(config):
    # Suppress dependency diagnostic payloads; emit only our content-free metrics below.
    for name in ("mcp", "mcp_types", "client", "httpx2", "httpcore", "httpcore2"):
        logger = logging.getLogger(name)
        logger.addHandler(logging.NullHandler())
        logger.propagate = False
    async with httpx2.AsyncClient(
        headers={"Authorization": f"Bearer {config.pat}",
                 "X-MCP-Tools": ",".join(ALLOWLIST), "X-MCP-Readonly": "true"},
        timeout=config.timeout_seconds,
        follow_redirects=False,
    ) as http:
        async with Client(
            streamable_http_client(config.url, http_client=http),
            read_timeout_seconds=config.timeout_seconds,
            cache=None,
        ) as client:
            yield client


class McpClientManager:
    def __init__(self, config: GitHubMcpConfig, *, client_factory=github_sdk_client):
        self.config = config
        self._factory = client_factory
        self._loop = None
        self._thread = None
        self._task = None
        self._stop = None
        self._client = None
        self._ready = Future()
        self.catalog = {}
        self.server_info = None
        self.protocol_version = None
        self.available = False
        self._closed = False
        self._pending = set()
        self._lock = threading.Lock()

    def start(self) -> bool:
        if not self.config.enabled or self._closed:
            return False
        if self._thread is not None:
            return self.available
        self._thread = threading.Thread(target=self._run, name="github-mcp", daemon=True)
        self._thread.start()
        try:
            self._ready.result(timeout=self.config.timeout_seconds + 1)
        except Exception:
            LOGGER.warning("mcp.server=github startup=degraded failure=connection_or_discovery")
            self.close()
        return self.available

    def _run(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        self._loop.set_exception_handler(
            lambda loop, context: LOGGER.warning("mcp.server=github background_failure=true")
        )
        self._task = self._loop.create_task(self._lifecycle())
        try:
            self._loop.run_until_complete(self._task)
        except BaseException:
            # Never expose upstream task/transport exceptions (including credential echoes).
            if not self._ready.done():
                self._ready.set_exception(GitHubMcpError("connection_failed"))
        finally:
            self.available = False
            self._client = None
            tasks = asyncio.all_tasks(self._loop)
            for task in tasks:
                task.cancel()
            self._loop.run_until_complete(asyncio.gather(*tasks, return_exceptions=True))
            self._loop.run_until_complete(self._loop.shutdown_asyncgens())
            self._loop.close()

    async def _lifecycle(self):
        self._stop = asyncio.Event()
        # Entry and exit stay in this SAME task: SDK/AnyIO cancellation scopes require it.
        async with self._factory(self.config) as client:
            self._client = client
            tools, cursor, seen = [], None, set()
            for _ in range(20):
                page = await asyncio.wait_for(
                    client.list_tools(cursor=cursor), self.config.timeout_seconds
                )
                tools.extend(page.tools)
                cursor = page.next_cursor
                if cursor is None:
                    break
                if cursor in seen:
                    raise GitHubMcpError("discovery_failed")
                seen.add(cursor)
            else:
                raise GitHubMcpError("discovery_failed")
            self.catalog = validate_catalog(tools)
            # Metadata is readable by the smoke script but is never an instruction to the LLM.
            self.protocol_version = client.protocol_version
            self.server_info = client.server_info
            self.available = True
            self._ready.set_result(True)
            await self._stop.wait()

    def call_tool(self, name: str, arguments: dict):
        if name not in ALLOWLIST or name not in self.catalog:
            raise GitHubMcpError("tool_unavailable")
        try:
            Draft202012Validator(self.catalog[name]).validate(arguments)
        except Exception:
            raise GitHubMcpError("invalid_arguments") from None
        with self._lock:
            if not self.available or self._closed or not self._loop.is_running():
                raise GitHubMcpError("unavailable")
            coroutine = self._call(name, arguments)
            try:
                future = asyncio.run_coroutine_threadsafe(coroutine, self._loop)
            except RuntimeError:
                coroutine.close()
                raise GitHubMcpError("unavailable") from None
            self._pending.add(future)
        try:
            # Two attempts, each bounded; short cancellation margin for the sync bridge.
            return future.result(timeout=2 * self.config.timeout_seconds + 1)
        except TimeoutError:
            future.cancel()
            raise GitHubMcpError("timeout") from None
        except GitHubMcpError:
            raise
        except Exception:
            raise GitHubMcpError("call_failed") from None
        finally:
            with self._lock:
                self._pending.discard(future)

    async def _call(self, name, arguments):
        started = time.monotonic()
        success, timeout = False, False
        try:
            for attempt in range(2):
                try:
                    result = await asyncio.wait_for(
                        self._client.call_tool(name, arguments), self.config.timeout_seconds
                    )
                    if result.is_error or result.result_type != "complete":
                        raise GitHubMcpError("remote_tool_error")
                    success = True
                    return result
                except (TimeoutError, httpx2.TimeoutException):
                    timeout = True
                    raise GitHubMcpError("timeout") from None
                except (httpx2.NetworkError, ConnectionError):
                    if attempt:
                        raise GitHubMcpError("network_failed") from None
                except MCPError as error:
                    if error.code == REQUEST_TIMEOUT:
                        timeout = True
                        raise GitHubMcpError("timeout") from None
                    raise GitHubMcpError("protocol_error") from None
                except GitHubMcpError:
                    raise
                except Exception:
                    raise GitHubMcpError("call_failed") from None
        finally:
            LOGGER.info("mcp.server=github mcp.tool=%s latency_ms=%d success=%s timeout=%s",
                        name, int((time.monotonic() - started) * 1000), success, timeout)

    def close(self):
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self.available = False
            for future in self._pending:
                future.cancel()
        if self._loop is not None and self._loop.is_running():
            def stop():
                if self._stop is not None and self._ready.done():
                    self._stop.set()
                elif self._task is not None:
                    self._task.cancel()
            try:
                self._loop.call_soon_threadsafe(stop)
            except RuntimeError:
                pass  # The lifecycle already completed and closed the loop.
        if self._thread is not None:
            self._thread.join(timeout=self.config.timeout_seconds + 2)
            if self._thread.is_alive() and self._loop.is_running():
                self._loop.call_soon_threadsafe(self._task.cancel)
                self._thread.join(timeout=2)
            if self._thread.is_alive():
                LOGGER.warning("mcp.server=github shutdown=incomplete")
