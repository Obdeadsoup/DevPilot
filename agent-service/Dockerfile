FROM python:3.12-slim AS build
WORKDIR /build
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY pyproject.toml README.md ./
COPY src src
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir .

FROM python:3.12-slim AS runtime
RUN groupadd --system devpilot \
    && useradd --system --gid devpilot --home-dir /app --shell /usr/sbin/nologin devpilot
COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    AGENT_GRPC_HOST=0.0.0.0 \
    AGENT_GRPC_PORT=50051 \
    AGENT_MODEL_MODE=deepseek
WORKDIR /app
USER devpilot
EXPOSE 50051
# Channel readiness 验证真实 gRPC 监听；Agent/DeepSeek/Tool 全链仍必须单独做应用层 E2E。
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=10 \
  CMD python -c "import grpc; c=grpc.insecure_channel('127.0.0.1:50051'); grpc.channel_ready_future(c).result(timeout=3); c.close()" || exit 1
ENTRYPOINT ["python", "-m", "devpilot_agent_service.rpc.server"]

