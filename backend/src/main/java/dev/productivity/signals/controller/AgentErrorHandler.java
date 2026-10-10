package dev.productivity.signals.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Returns Agent errors as JSON with the reason in "detail". The default error page is HTML, which the
 * frontend discards, so it could not tell a busy Agent or a question limit from a GitHub rate limit.
 */
@RestControllerAdvice(assignableTypes = AgentController.class)
public class AgentErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .headers(error.getHeaders())
                .body(Map.of("status", error.getStatusCode().value(),
                        "detail", error.getReason() == null ? "" : error.getReason()));
    }
}
