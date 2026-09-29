package dev.productivity.signals.repository;

import dev.productivity.signals.entity.AiEvaluationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiEvaluationFeedbackRepository extends JpaRepository<AiEvaluationFeedback, Long> {
    Optional<AiEvaluationFeedback> findByEvaluationIdAndSectionAndItemIndex(
            UUID evaluationId, String section, int itemIndex);

    List<AiEvaluationFeedback> findByEvaluationId(UUID evaluationId);
}
