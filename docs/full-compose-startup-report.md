# DevPilot Full Compose Startup Report

## Baseline

- Starting branch: `main`.
- Starting HEAD: `481f010c0cc24d5d0396a437bd7d6351ca27f622`.
- Starting working tree was dirty. Its `git status --short` output was:

```text
 M README.md
 M agent-service/pyproject.toml
 M agent-service/src/devpilot_agent_service/context/budget.py
 M agent-service/src/devpilot_agent_service/context/manager.py
 M agent-service/src/devpilot_agent_service/graph/nodes/agent.py
 M agent-service/src/devpilot_agent_service/graph/nodes/tools.py
 M agent-service/src/devpilot_agent_service/graph/state.py
 M agent-service/src/devpilot_agent_service/graph/workflow.py
 M agent-service/src/devpilot_agent_service/harness/runtime.py
 M agent-service/src/devpilot_agent_service/harness/workflow.py
 M agent-service/src/devpilot_agent_service/rpc/generated/agent_runtime_pb2.py
 M agent-service/src/devpilot_agent_service/rpc/langgraph_application.py
 M agent-service/src/devpilot_agent_service/rpc/server.py
 M agent-service/src/devpilot_agent_service/rpc/servicer.py
 M agent-service/src/devpilot_agent_service/runtime/context.py
 M agent-service/tests/test_agent_workflow.py
 M agent-service/tests/test_cancel_resume_rpc.py
 M agent-service/tests/test_rpc_server.py
 M agent-service/tests/test_rpc_servicer.py
 M agent-service/tests/test_workflow_grpc.py
 M compose.yaml
 M contracts/agent/v1/agent_runtime.proto
 M devpilot-agent/src/main/java/com/obdeadsoup/devpilot/agent/application/AgentRunApplicationService.java
 M devpilot-agent/src/main/java/com/obdeadsoup/devpilot/agent/application/AgentRunCommand.java
 M devpilot-agent/src/main/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/GrpcAgentRuntimeClient.java
 M devpilot-agent/src/main/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/GrpcAgentRuntimeStreamingClient.java
 M devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/application/AgentRunApplicationServiceTest.java
 M devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/GrpcAgentRuntimeClientTest.java
 M devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/GrpcAgentRuntimeStreamingClientTest.java
 M ops/docker/core.Dockerfile
 M ops/docker/gateway.Dockerfile
 M ops/docker/mvn-with-proxy.sh
?? agent-service/evals/
?? agent-service/src/devpilot_agent_service/eval/
?? agent-service/src/devpilot_agent_service/graph/checkpoint.py
?? agent-service/src/devpilot_agent_service/memory/
?? agent-service/tests/test_context_v2.py
?? agent-service/tests/test_evaluation.py
?? agent-service/tests/test_unified_runtime.py
?? devpilot-agent/src/main/java/com/obdeadsoup/devpilot/agent/application/AgentExecutionScope.java
```

- The requested baseline diff showed that the MinIO Quay image and Knowledge Maven `COPY` lines were already present as local modifications. Core still had `dependency:go-offline -DexcludeScope=test`; Gateway had unscoped `dependency:go-offline`; both package steps used `-DskipTests`.
- During this run, another process advanced local `main` and `origin/main` to `9c62bc4cfbe295e2de083b8b5b561390552af44b`, committing the previously dirty files and this run's initial Dockerfile/.gitattributes changes. No commit, push, reset, checkout, merge, or cherry-pick was run in this session. Subsequent validation used the unchanged file contents on `main`.

## Root Causes Found

1. The old `minio/minio` tag was not the intended pull source. The baseline local diff already selected `quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z`; `docker pull` confirmed it is available.
2. The repository only pinned `ops/nacos/publish-config.sh` to LF. Both tracked container shell scripts currently have zero CRLF sequences; the attributes now pin every `*.sh` to LF.
3. The root Maven reactor includes `devpilot-knowledge`. The baseline local Dockerfile edits had already added its POM to both images and source to Core. Those lines were kept.
4. Docker runtime builds used `dependency:go-offline`, which can prefetch test dependencies, and `-DskipTests`, which does not skip test compilation. Both prefetch layers were removed, and both package steps now use `-Dmaven.test.skip=true` with the BuildKit `/root/.m2` cache retained. The earlier Testcontainers/Maven Central interruption reported in the task was not reproduced in this run.
5. Both Knowledge inference services remain unavailable while downloading large ONNX weights from Hugging Face. Their `/health` endpoints never opened during observation, and they restarted while downloading. Container network tests received only 1.38 MB of a 10 MB embedding range in 30 seconds and 10 MB of a reranker range in 24.5 seconds. The selected upstream files are [470 MB](https://huggingface.co/intfloat/multilingual-e5-small/tree/main/onnx) and [1.11 GB](https://huggingface.co/BAAI/bge-reranker-base/tree/main/onnx). This is the remaining external model download blocker. Compose now reports their actual health state instead of showing only `Up`.
6. Host `curl` inherited a proxy and returned 502 for every localhost port. `curl --noproxy '*'` returned HTTP 200 from the same endpoints, confirming this was a host request-routing issue rather than service failure.

## Files Modified

| File | Change and reason |
| --- | --- |
| `.gitattributes` | Added `*.sh text eol=lf` so Linux container scripts stay LF on Windows checkouts. |
| `.gitignore` | Allowed this required report under the otherwise ignored `docs/*` directory so the file is visible in the working tree. |
| `ops/docker/core.Dockerfile` | Removed `dependency:go-offline`, changed packaging to `-Dmaven.test.skip=true`, retained Knowledge POM/source copies and Maven cache. |
| `ops/docker/gateway.Dockerfile` | Removed `dependency:go-offline`, changed packaging to `-Dmaven.test.skip=true`, retained Knowledge POM copy and Maven cache. |
| `compose.yaml` | Retained the baseline Quay MinIO correction; added `/health` checks for the embedding and reranker containers so model download failures are visible. |
| `docs/full-compose-startup-report.md` | Recorded the baseline, builds, runtime checks, and remaining blocker. |

`ops/docker/mvn-with-proxy.sh` was inspected for LF and was not edited in this session.

## Runtime Docker Build Policy

- Docker runtime builds skip test compilation and test execution with `-Dmaven.test.skip=true`.
- Docker runtime builds do not run `dependency:go-offline`, so they do not prefetch test scope. BuildKit caches `/root/.m2`.
- CI still runs `mvn -B -ntp clean verify` in `.github/workflows/backend-ci.yml`. Testcontainers remains declared in `devpilot-boot/pom.xml`; no tests or test dependencies were removed.

## Build Results

| Service | Result | Duration | Notes |
| --- | --- | --- | --- |
| `devpilot-core` | PASS | About 5 min 20 s | Maven reported `BUILD SUCCESS` in 5 min 14 s; test compilation and execution skipped. |
| `devpilot-gateway` | PASS | About 1 min | Maven reported `BUILD SUCCESS` in 55.9 s; test compilation and execution skipped. |
| `agent-service` | PASS | 2.6 s | Successful cached image build. |
| `devpilot-web` | PASS | 4.3 s | Successful cached typecheck/build image layers. |

No runtime Maven build failed on Testcontainers, JUnit, Mockito, or another test dependency.

## Compose Status

`docker compose --profile full up -d` returned successfully, including a final repeat with the TEI health checks in place. The table below records the final `docker compose --profile full ps -a` snapshot. Container-only ports are not published on localhost. At final inspection each TEI container had restarted six times and was `unhealthy`.

| Service | Image | Status | Health | Port |
| --- | --- | --- | --- | --- |
| `agent-service` | `devpilot-agent-service:local` | running | healthy | 50051/tcp, internal |
| `devpilot-core` | `devpilot-core:local` | running | healthy | localhost:8080 -> 8080 |
| `devpilot-gateway` | `devpilot-gateway:local` | running | healthy | localhost:8081 -> 8081 |
| `devpilot-web` | `devpilot-web:local` | running | healthy | localhost:5173 -> 80 |
| `knowledge-embedding` | `ghcr.io/huggingface/text-embeddings-inference:cpu-1.9` | running, 6 restarts | unhealthy; `/health` unavailable | 80/tcp, internal |
| `knowledge-reranker` | `ghcr.io/huggingface/text-embeddings-inference:cpu-1.9` | running, 6 restarts | unhealthy; `/health` unavailable | 80/tcp, internal |
| `mailpit` | `axllent/mailpit:v1.30.4` | running | healthy | localhost:8025 -> 8025 |
| `minio` | `quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z` | running | healthy | localhost:9000-9001 |
| `mysql` | `mysql:8.4` | running | healthy | localhost:3307 -> 3306 |
| `nacos-config-init` | `curlimages/curl:8.16.0` | exited (0) | completed successfully | none |
| `nacos` | `nacos/nacos-server:v3.0.3` | running | healthy | localhost:8082 -> 8080, 8848, 9848 |
| `redis` | `redis:7.4-alpine` | running | healthy | localhost:6380 -> 6379 |

## HTTP Smoke

All localhost requests below used `curl.exe --noproxy '*'` to bypass the host proxy.

| Target | Result |
| --- | --- |
| Core `/actuator/health` | HTTP 200, `UP` |
| Core `/actuator/health/readiness` | HTTP 200, `UP` |
| Gateway `/actuator/health` | HTTP 200, `UP` |
| Web `/healthz` | HTTP 200, `ok` |
| Web `/` | HTTP 200 |
| Mailpit `/` | HTTP 200 |
| MinIO Console `/` | HTTP 200 |
| Core -> MinIO `/minio/health/live` | HTTP 200 |
| Core -> Knowledge embedding `/health` | Connection refused during model download |
| Core -> Knowledge reranker `/health` | Connection refused during model download |

Core has `DEVPILOT_KNOWLEDGE_STORAGE_MODE=minio` and `DEVPILOT_MINIO_ENDPOINT=http://minio:9000`. The MinIO access key, secret key, and bucket variables are present without printing their values. The MinIO `devpilot-knowledge` bucket exists; `MinioKnowledgeObjectStorage` creates it at initialization. Agent gRPC `:50051` passes its container health check, Core Tool Gateway `:50052` accepts TCP connections, and the service key is present on both sides without printing it. No provider API call was made.

## Remaining Runtime Issues

- **Full Compose readiness is incomplete.** Both TEI containers are unable to finish downloading their ONNX weights from Hugging Face in this environment, so Knowledge embedding and reranking are not healthy. The other services and requested HTTP endpoints are healthy. Their model cache volumes were preserved; no volumes were deleted.
- The DeepSeek and GitHub providers were not exercised. Their external availability is not a Docker build result.

## Exact Commands Used

```text
git status --short --branch
git branch --show-current
git rev-parse HEAD
git diff -- compose.yaml ops/docker/core.Dockerfile ops/docker/gateway.Dockerfile ops/docker/mvn-with-proxy.sh .gitattributes pom.xml
docker pull quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z
docker compose --profile full config --quiet
docker compose --profile full build devpilot-core --progress=plain
docker compose --profile full build devpilot-gateway --progress=plain
docker compose --profile full build agent-service --progress=plain
docker compose --profile full build devpilot-web --progress=plain
docker compose --profile full up -d
docker compose --profile full up -d --no-deps knowledge-embedding knowledge-reranker
docker compose --profile full ps -a
curl.exe --noproxy '*' --max-time 10 -sS -w ' HTTP=%{http_code}' http://localhost:8080/actuator/health
curl.exe --noproxy '*' --max-time 10 -sS -w ' HTTP=%{http_code}' http://localhost:8080/actuator/health/readiness
curl.exe --noproxy '*' --max-time 10 -sS -w ' HTTP=%{http_code}' http://localhost:8081/actuator/health
curl.exe --noproxy '*' --max-time 10 -sS -w ' HTTP=%{http_code}' http://localhost:5173/healthz
```

The four image builds and `up -d` succeeded. The defined full-readiness goal remains blocked by Hugging Face model transfer and the resulting TEI restart/unhealthy state.
