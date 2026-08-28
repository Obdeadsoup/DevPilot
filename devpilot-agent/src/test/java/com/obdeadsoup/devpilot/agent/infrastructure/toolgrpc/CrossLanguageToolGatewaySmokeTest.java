package com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc;

import com.obdeadsoup.devpilot.agent.application.tool.AgentToolApplicationService;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolResult;
import com.obdeadsoup.devpilot.agent.config.AgentToolGrpcProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Java Server 与独立 Python Client/AgentLoop 通过真实 TCP/HTTP2 联调；普通 CI 默认条件跳过。 */
@EnabledIfEnvironmentVariable(named = "DEVPILOT_AGENT_TOOL_CROSS_LANGUAGE_SMOKE", matches = "true")
class CrossLanguageToolGatewaySmokeTest {
    private static final String SECRET = "p0-07-cross-language-test-key";

    @Test
    void pythonProcessCallsJavaGatewayThenRunsRemoteToolAgentLoop() throws Exception {
        AgentToolApplicationService application = mock(AgentToolApplicationService.class);
        when(application.execute(any())).thenAnswer(invocation -> {
            var command = (com.obdeadsoup.devpilot.agent.application.tool.AgentToolCommand)
                    invocation.getArgument(0);
            return new AgentToolResult(
                    command.requestId() + ":" + command.toolCallId(), command.toolCallId(),
                    Map.of("tool", command.toolName(), "external_untrusted_content", true));
        });
        AgentToolGrpcProperties properties = new AgentToolGrpcProperties(
                true, "127.0.0.1", 0, SECRET, 65_536, 65_536, 2, 16, Duration.ofSeconds(2));
        AgentToolGatewayMetrics metrics = new AgentToolGatewayMetrics(new SimpleMeterRegistry());
        var service = new DevPilotToolGatewayGrpcService(application, properties, metrics);
        var lifecycle = new AgentToolGrpcServerLifecycle(properties, service,
                new AgentToolServiceAuthInterceptor(SECRET, metrics));
        lifecycle.start();
        try {
            Path repository = Path.of("..").toAbsolutePath().normalize();
            Path python = repository.resolve(".venv/Scripts/python.exe");
            if (!Files.isRegularFile(python)) {
                python = Path.of("python");
            }
            ProcessBuilder builder = new ProcessBuilder(
                    python.toString(),
                    repository.resolve("agent-service/tests/cross_language_tool_smoke.py").toString());
            builder.directory(repository.toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("PYTHONPATH",
                    repository.resolve("agent-service/src").toString());
            builder.environment().put("DEVPILOT_JAVA_TOOL_GRPC_TARGET",
                    "127.0.0.1:" + lifecycle.boundPort());
            builder.environment().put("DEVPILOT_AGENT_TOOL_SERVICE_KEY", SECRET);
            // 本地双进程联调必须绕过开发机代理，否则 gRPC loopback 会被错误送往 HTTP proxy。
            builder.environment().put("NO_PROXY", "127.0.0.1,localhost");
            builder.environment().put("no_proxy", "127.0.0.1,localhost");
            for (String proxy : new String[]{
                    "GRPC_PROXY", "grpc_proxy", "HTTP_PROXY", "http_proxy",
                    "HTTPS_PROXY", "https_proxy"}) {
                builder.environment().remove(proxy);
            }
            Process process = builder.start();
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
            }
            String output = new String(process.getInputStream().readAllBytes());
            assertThat(completed).as(output).isTrue();
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("P0_07_CROSS_LANGUAGE_TOOL_PASS");
        } finally {
            lifecycle.stop();
        }
    }
}
