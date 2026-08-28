package com.obdeadsoup.devpilot.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdPolicy;
import com.obdeadsoup.devpilot.gateway.config.GatewayRequestIdFilter;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.gateway.server.webflux.httpclient.response-timeout=100ms")
class GatewayIntegrationTest {

    private static final DisposableServer UPSTREAM = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes
                    .get("/api/v1/notifications/stream", (request, response) -> response
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .sendString(Flux.just("data:first\n\n", "data:second\n\n")
                                    .delayElements(Duration.ofMillis(200))))
                    .get("/api/rest", (request, response) -> response
                            .header("X-Upstream-Authorization", String.valueOf(
                                    request.requestHeaders().contains(HttpHeaders.AUTHORIZATION)))
                            .header("X-Upstream-Request-Id", request.requestHeaders()
                                    .get(GatewayRequestIdFilter.REQUEST_ID_HEADER))
                            .header("X-Upstream-Correlation-Id", request.requestHeaders()
                                    .get(CorrelationIdPolicy.HEADER_NAME))
                            .sendString(Flux.just("proxied"))))
            .bindNow();

    @Autowired
    private WebTestClient client;

    @Autowired
    private RouteLocator routeLocator;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.cloud.discovery.client.simple.instances.devpilot-core[0].uri",
                () -> "http://127.0.0.1:" + UPSTREAM.port());
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.disposeNow();
    }

    @Test
    void apiRouteForwardsAuthorizationAndOneNormalizedRequestId() {
        client.get()
                .uri("/api/rest")
                .header(HttpHeaders.AUTHORIZATION, "Bearer integration-test-token")
                .header(GatewayRequestIdFilter.REQUEST_ID_HEADER, "request-id-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Upstream-Authorization", "true")
                .expectHeader().valueEquals("X-Upstream-Request-Id", "request-id-123")
                .expectHeader().valueEquals("X-Upstream-Correlation-Id", "request-id-123")
                .expectHeader().valueEquals(GatewayRequestIdFilter.REQUEST_ID_HEADER, "request-id-123")
                .expectHeader().valueEquals(CorrelationIdPolicy.HEADER_NAME, "request-id-123")
                .expectBody(String.class).isEqualTo("proxied");
    }

    @Test
    void corsPreflightAllowsConfiguredFrontendAndAuthorizationHeader() {
        client.options()
                .uri("/api/rest")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethodName.POST)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value).containsIgnoringCase(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void sseRouteDisablesShortGlobalResponseTimeout() {
        Flux<String> body = client.get()
                .uri("/api/v1/notifications/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(body)
                .expectNextMatches(value -> value.contains("first"))
                .thenCancel()
                .verify(Duration.ofSeconds(3));
    }

    @Test
    void nonApiPathIsNotRoutedAndGatewayHealthIsLocal() {
        client.get().uri("/not-api").exchange().expectStatus().isNotFound();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void routesUseOnlyLoadBalancedCoreAndSseHasNoResponseTimeout() {
        Map<String, Route> routes = routeLocator.getRoutes()
                .filter(route -> route.getId().startsWith("devpilot-core-"))
                .collect(Collectors.toMap(Route::getId, Function.identity()))
                .block(Duration.ofSeconds(5));

        assertThat(routes).containsOnlyKeys("devpilot-core-sse", "devpilot-core-api");
        assertThat(routes.values()).allSatisfy(route -> assertThat(route.getUri().toString())
                .isEqualTo("lb://devpilot-core"));
        assertThat(routes.get("devpilot-core-sse").getMetadata())
                .containsEntry("response-timeout", -1);
    }

    private static final class HttpMethodName {
        private static final String POST = "POST";

        private HttpMethodName() {
        }
    }
}
