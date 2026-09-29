package dev.productivity.signals.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerTest {
    @Test
    void forwardsSuccessfulAgentAnswerWithoutChangingIt() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var forwardedRequestId = new AtomicReference<String>();
        var forwardedModel = new AtomicReference<String>();
        var body = """
                {"requestId":"request-1","executionId":"execution-1","answer":"Facts: 62.\\nAdvice: review changes.","sources":[],"iterations":2}
                """;
        server.createContext("/ask", exchange -> {
            forwardedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            forwardedModel.set(new ObjectMapper().readTree(exchange.getRequestBody()).path("model").asText());
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var controller = configuredController(server);
            var response = new ObjectMapper().readTree(controller.ask(validRequest()).getBody());
            assertThat(response.path("answer").asText()).isEqualTo("Facts: 62.\nAdvice: review changes.");
            assertThat(response.path("requestId").asText()).isEqualTo("request-1");
            assertThat(response.path("sources").isArray()).isTrue();
            assertThat(forwardedRequestId.get()).isNotBlank();
            assertThat(forwardedModel.get()).isEqualTo("gemini-3.8-flash");

            MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(post("/api/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(validRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").value("Facts: 62.\nAdvice: review changes."))
                    .andExpect(jsonPath("$.requestId").value("request-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedSuccessfulAgentResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ask", exchange -> {
            var bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var controller = configuredController(server);
            assertThatThrownBy(() -> controller.ask(validRequest()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        } finally {
            server.stop(0);
        }
    }

    private AgentController configuredController(HttpServer server) {
        var controller = new AgentController();
        ReflectionTestUtils.setField(controller, "enabled", true);
        ReflectionTestUtils.setField(controller, "internalKey", "test-internal-key");
        ReflectionTestUtils.setField(controller, "serviceUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        return controller;
    }

    private AgentController.AskRequest validRequest() {
        return new AgentController.AskRequest("Summarize metrics", "github~openai~openai-java",
                "2026-09-01", "2026-09-07", "main", "gemini-3.8-flash");
    }

    @Test
    void startsWithoutAnObjectMapperBean() {
        new ApplicationContextRunner()
                .withBean(AgentController.class)
                .run(context -> assertThat(context).hasSingleBean(AgentController.class));
    }

    @Test
    void disabledByDefault() {
        var controller = new AgentController();
        var request = new AgentController.AskRequest(
                "Why did productivity change?", "github~openai~openai-java",
                "2026-09-01", "2026-09-07", "main", null);
        assertThatThrownBy(() -> controller.ask(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void rejectsDemoProjectsBeforeCallingAgent() {
        var controller = new AgentController();
        ReflectionTestUtils.setField(controller, "enabled", true);
        ReflectionTestUtils.setField(controller, "internalKey", "test-internal-key");
        var request = new AgentController.AskRequest(
                "Why did productivity change?", "demo~project",
                "2026-09-01", "2026-09-07", "main", null);
        assertThatThrownBy(() -> controller.ask(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsConcurrentAgentRequest() {
        var controller = new AgentController();
        ReflectionTestUtils.setField(controller, "enabled", true);
        ReflectionTestUtils.setField(controller, "internalKey", "test-internal-key");
        Semaphore permit = (Semaphore) ReflectionTestUtils.getField(controller, "requestPermit");
        permit.acquireUninterruptibly();
        try {
            var request = new AgentController.AskRequest(
                    "Why did productivity change?", "github~openai~openai-java",
                    "2026-09-01", "2026-09-07", "main", null);
            assertThatThrownBy(() -> controller.ask(request))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        } finally {
            permit.release();
        }
    }

    @Test
    void rejectsUnsupportedModel() {
        var controller = new AgentController();
        ReflectionTestUtils.setField(controller, "enabled", true);
        ReflectionTestUtils.setField(controller, "internalKey", "test-internal-key");
        var request = new AgentController.AskRequest(
                "Summarize metrics", "github~openai~openai-java",
                "2026-09-01", "2026-09-07", "main", "gemini-unknown");
        assertThatThrownBy(() -> controller.ask(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
