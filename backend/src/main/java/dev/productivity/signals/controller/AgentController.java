package dev.productivity.signals.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.productivity.signals.service.AgentRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
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
    private final AgentRateLimiter rateLimiter;

    @Value("${agent.enabled:false}")
    private boolean enabled;

    @Value("${agent.service-url:http://localhost:8000}")
    private String serviceUrl;

    @Value("${agent.internal-key:}")
    private String internalKey;

    /** Without a rate limiter, for tests that exercise the proxy alone. */
    public AgentController() {
        this(new AgentRateLimiter(0, 0, Clock.systemUTC()));
    }

    @Autowired
    public AgentController(AgentRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public record AskRequest(
            String question,
            String projectId,
            String since,
            String until,
            String refName,
            String model) {
    }

    public ResponseEntity<String> ask(AskRequest request) {
        return ask(request, null);
    }

    /** Validation shared by both endpoints; runs before any quota is used. */
    private void validate(AskRequest request) {
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
        try {
            LocalDate since = LocalDate.parse(request.since());
            LocalDate until = LocalDate.parse(request.until());
            if (until.isBefore(since) || until.isAfter(LocalDate.now().plusDays(1))
                    || since.isBefore(until.minusDays(365))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
            }
        } catch (java.time.format.DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format", error);
        }
    }

    /**
     * Takes the single busy permit, then a rate-limit slot, so a busy rejection does not use a client's quota.
     * The caller releases the permit.
     */
    private void acquire(HttpServletRequest httpRequest, String requestId) {
        if (!requestPermit.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent is busy");
        }
        var rejection = rateLimiter.tryAcquire(clientAddress(httpRequest));
        if (rejection.isPresent()) {
            requestPermit.release();
            log.info("AGENT_RATE_LIMITED requestId={} reason={}", requestId, rejection.get().reason());
            String retryAfter = String.valueOf(rejection.get().retryAfterSeconds());
            // AgentErrorHandler copies these headers into the 429 response.
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, rejection.get().reason()) {
                @Override
                public org.springframework.http.HttpHeaders getHeaders() {
                    var headers = new org.springframework.http.HttpHeaders();
                    headers.set("Retry-After", retryAfter);
                    return headers;
                }
            };
        }
    }

    private HttpRequest upstream(String path, AskRequest request, String requestId) throws IOException {
        return HttpRequest.newBuilder(URI.create(serviceUrl + path))
                .timeout(Duration.ofSeconds(270))
                .header("Content-Type", "application/json")
                .header("X-Agent-Internal-Key", internalKey)
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
    }

    /**
     * Server-sent events from the Agent, relayed as they arrive: step, thought and answer progress, then a
     * "done" event with the full answer or an "error" event with the status the JSON endpoint would return.
     */
    @PostMapping("/ask/stream")
    public ResponseEntity<StreamingResponseBody> askStream(@RequestBody AskRequest request,
            HttpServletRequest httpRequest) {
        validate(request);
        String requestId = UUID.randomUUID().toString();
        acquire(httpRequest, requestId);
        StreamingResponseBody body = output -> {
            try {
                HttpResponse<InputStream> response = httpClient.send(upstream("/ask/stream", request, requestId),
                        HttpResponse.BodyHandlers.ofInputStream());
                log.info("AGENT_PROXY_STREAM requestId={} upstreamStatus={}", requestId, response.statusCode());
                try (InputStream input = response.body()) {
                    if (response.statusCode() != 200) {
                        output.write(errorEvent(response.statusCode() == 503 ? 503 : 502,
                                response.statusCode() == 503 ? "Gemini service temporarily unavailable"
                                        : "Agent request failed"));
                        output.flush();
                        return;
                    }
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        output.flush();
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                log.warn("AGENT_PROXY_STREAM_FAILURE requestId={} errorType=InterruptedException", requestId);
            } catch (IOException error) {
                // Also reached when the browser goes away; closing the upstream stream stops the Agent.
                log.warn("AGENT_PROXY_STREAM_FAILURE requestId={} errorType={}", requestId,
                        error.getClass().getSimpleName());
                try {
                    output.write(errorEvent(502, "Agent unavailable"));
                    output.flush();
                } catch (IOException ignored) {
                    // The client is gone.
                }
            } finally {
                requestPermit.release();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private byte[] errorEvent(int status, String detail) throws IOException {
        String json = objectMapper.writeValueAsString(java.util.Map.of("type", "error", "status", status,
                "detail", detail));
        return ("data: " + json + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @PostMapping("/ask")
    public ResponseEntity<String> ask(@RequestBody AskRequest request, HttpServletRequest httpRequest) {
        validate(request);
        String requestId = UUID.randomUUID().toString();
        acquire(httpRequest, requestId);
        try {
            HttpResponse<String> response = httpClient.send(upstream("/ask", request, requestId), HttpResponse.BodyHandlers.ofString());
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
        } catch (Exception error) {
            log.warn("AGENT_PROXY_FAILURE requestId={} errorType={}", requestId,
                    error.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent unavailable", error);
        } finally {
            requestPermit.release();
        }
    }

    /** The last X-Forwarded-For entry is the one Caddy added, so a client cannot choose its own key. */
    static String clientAddress(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
