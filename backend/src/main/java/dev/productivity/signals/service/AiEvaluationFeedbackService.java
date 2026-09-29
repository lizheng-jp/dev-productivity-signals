package dev.productivity.signals.service;

import dev.productivity.signals.dto.AiEvaluationResponseDTO;
import dev.productivity.signals.entity.AiEvaluationFeedback;
import dev.productivity.signals.entity.AiEvaluationSnapshot;
import dev.productivity.signals.repository.AiEvaluationFeedbackRepository;
import dev.productivity.signals.repository.AiEvaluationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiEvaluationFeedbackService {
    private static final Set<String> SECTIONS = Set.of("strengths", "weaknesses", "suggestions");

    private final AiEvaluationSnapshotRepository snapshots;
    private final AiEvaluationFeedbackRepository feedback;

    public record FeedbackRequest(UUID evaluationId, String section, int itemIndex, Boolean helpful) {
    }

    public record FeedbackResponse(String section, int itemIndex, boolean helpful) {
    }

    @Transactional
    public AiEvaluationResponseDTO capture(
            AiEvaluationResponseDTO output,
            String projectId,
            String since,
            String until,
            String userName,
            String refName,
            boolean aiEnabled) {
        if (output == null) {
            return null;
        }

        AiEvaluationSnapshot snapshot = new AiEvaluationSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setSelectionJson(new JSONObject()
                .put("projectId", projectId)
                .put("since", nullable(since))
                .put("until", nullable(until))
                .put("userName", nullable(userName))
                .put("refName", nullable(refName))
                .put("aiEnabled", aiEnabled)
                .toString());
        snapshot.setOutputJson(new JSONObject()
                .put("strengths", new JSONArray(output.getStrengths()))
                .put("weaknesses", new JSONArray(output.getWeaknesses()))
                .put("suggestions", new JSONArray(output.getSuggestions()))
                .toString());
        snapshot.setCreatedAt(Instant.now());
        snapshots.saveAndFlush(snapshot);
        output.setEvaluationId(snapshot.getId().toString());
        return output;
    }

    @Transactional
    public FeedbackResponse save(FeedbackRequest request) {
        if (request == null || request.evaluationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evaluation is required");
        }
        if (!SECTIONS.contains(request.section() == null ? "" : request.section()) || request.helpful() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid feedback");
        }

        AiEvaluationSnapshot snapshot = snapshots.findById(request.evaluationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation not found"));
        JSONArray items = new JSONObject(snapshot.getOutputJson()).getJSONArray(request.section());
        if (request.itemIndex() < 0 || request.itemIndex() >= items.length()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item");
        }

        Instant now = Instant.now();
        AiEvaluationFeedback entry = feedback
                .findByEvaluationIdAndSectionAndItemIndex(
                        request.evaluationId(), request.section(), request.itemIndex())
                .orElseGet(() -> {
                    AiEvaluationFeedback created = new AiEvaluationFeedback();
                    created.setCreatedAt(now);
                    return created;
                });
        entry.setEvaluationId(request.evaluationId());
        entry.setSection(request.section());
        entry.setItemIndex(request.itemIndex());
        entry.setItemText(items.getString(request.itemIndex()));
        entry.setHelpful(request.helpful());
        entry.setUpdatedAt(now);
        feedback.saveAndFlush(entry);
        return new FeedbackResponse(entry.getSection(), entry.getItemIndex(), entry.isHelpful());
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> list(UUID evaluationId) {
        if (evaluationId == null || !snapshots.existsById(evaluationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation not found");
        }
        return feedback.findByEvaluationId(evaluationId).stream()
                .map(entry -> new FeedbackResponse(
                        entry.getSection(), entry.getItemIndex(), entry.isHelpful()))
                .toList();
    }

    private Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
