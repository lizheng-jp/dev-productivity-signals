package dev.productivity.signals.controller;

import dev.productivity.signals.service.AiEvaluationFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai/evaluation-feedback")
@RequiredArgsConstructor
public class AiEvaluationFeedbackController {
    private final AiEvaluationFeedbackService service;

    @PostMapping
    public AiEvaluationFeedbackService.FeedbackResponse save(
            @RequestBody AiEvaluationFeedbackService.FeedbackRequest request) {
        return service.save(request);
    }

    @GetMapping
    public List<AiEvaluationFeedbackService.FeedbackResponse> list(@RequestParam UUID evaluationId) {
        return service.list(evaluationId);
    }
}
