package dev.productivity.signals.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final Set<String> ALLOWED_MODELS = Set.of(
            "gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.7-flash",
            "gemini-3.8-flash", "gemini-3.5-flash-lite");
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Semaphore requestPermit = new Semaphore(1);

    @Value("${agent.enabled:false}")
    private boolean enabled;

    @Value("${agent.service-url:http://localhost:8000}")
    private String serviceUrl;

    @Value("${agent.internal-key:}")
    private String internalKey;

    public record AskRequest(
            String question,
            String projectId,
            String since,
            String until,
            String refName,
            String model) {
    }

    @PostMapping("/ask")
    public ResponseEntity<String> ask(@RequestBody AskRequest request) {
        if (!enabled || internalKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent is not configured");
        }
        if (request.question() == null || request.question().isBlank() || request.question().length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question must be 1-1000 characters");
        }
        if (request.projectId() == null
                || !request.projectId().matches("github~[A-Za-z0-9_.-]+~[A-Za-z0-9_.-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a real GitHub project");
        }
        if (request.since() == null || request.until() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A date range is required");
        }
        if (request.model() != null && !ALLOWED_MODELS.contains(request.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported Agent model");
        }
        if (!requestPermit.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent is busy");
        }
        String requestId = UUID.randomUUID().toString();
        try {
            LocalDate since = LocalDate.parse(request.since());
            LocalDate until = LocalDate.parse(request.until());
            if (until.isBefore(since) || until.isAfter(LocalDate.now().plusDays(1))
                    || since.isBefore(until.minusDays(365))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serviceUrl + "/ask"))
                    .timeout(Duration.ofSeconds(270))
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Internal-Key", internalKey)
                    .header("X-Request-Id", requestId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            log.info("AGENT_PROXY requestId={} upstreamStatus={}", requestId, response.statusCode());
            if (response.statusCode() == 429) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GitHub API rate limit reached");
            }
            if (response.statusCode() == 503) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini service temporarily unavailable");
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent request failed");
            }
            JsonNode result = objectMapper.readTree(response.body());
            if (result == null || !result.path("answer").isTextual()
                    || result.path("answer").asText().isBlank() || !result.path("sources").isArray()) {
                log.warn("AGENT_PROXY_INVALID_RESPONSE requestId={}", requestId);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid Agent response");
            }
            log.info("AGENT_PROXY_SUCCESS requestId={} answerChars={}", requestId,
                    result.path("answer").asText().length());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.body());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (java.time.format.DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format", error);
        } catch (Exception error) {
            log.warn("AGENT_PROXY_FAILURE requestId={} errorType={}", requestId,
                    error.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent unavailable", error);
        } finally {
            requestPermit.release();
        }
    }
}
