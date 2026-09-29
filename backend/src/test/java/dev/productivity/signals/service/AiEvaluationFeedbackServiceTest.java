package dev.productivity.signals.service;

import dev.productivity.signals.dto.AiEvaluationResponseDTO;
import dev.productivity.signals.entity.AiEvaluationFeedback;
import dev.productivity.signals.entity.AiEvaluationSnapshot;
import dev.productivity.signals.repository.AiEvaluationFeedbackRepository;
import dev.productivity.signals.repository.AiEvaluationSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEvaluationFeedbackServiceTest {

    @Test
    void capturesEvaluationAndStoresVote() {
        AiEvaluationSnapshotRepository snapshots = mock(AiEvaluationSnapshotRepository.class);
        AiEvaluationFeedbackRepository feedback = mock(AiEvaluationFeedbackRepository.class);
        when(snapshots.saveAndFlush(any(AiEvaluationSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AiEvaluationFeedbackService service = new AiEvaluationFeedbackService(snapshots, feedback);

        AiEvaluationResponseDTO evaluation = service.capture(
                new AiEvaluationResponseDTO(List.of("Strong tests"), List.of("Slow review"), List.of("Split changes")),
                "1", "2026-09-01", "2026-09-07", "demo", "main", true);
        UUID evaluationId = UUID.fromString(evaluation.getEvaluationId());

        AiEvaluationSnapshot snapshot = new AiEvaluationSnapshot();
        snapshot.setId(evaluationId);
        snapshot.setOutputJson("{\"strengths\":[\"Strong tests\"],\"weaknesses\":[\"Slow review\"],\"suggestions\":[\"Split changes\"]}");
        when(snapshots.findById(evaluationId)).thenReturn(Optional.of(snapshot));
        when(feedback.findByEvaluationIdAndSectionAndItemIndex(evaluationId, "strengths", 0))
                .thenReturn(Optional.empty());
        when(feedback.saveAndFlush(any(AiEvaluationFeedback.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AiEvaluationFeedbackService.FeedbackResponse response = service.save(
                new AiEvaluationFeedbackService.FeedbackRequest(evaluationId, "strengths", 0, true));

        assertThat(response.helpful()).isTrue();
        assertThat(response.section()).isEqualTo("strengths");
        verify(snapshots).saveAndFlush(any(AiEvaluationSnapshot.class));
        verify(feedback).saveAndFlush(any(AiEvaluationFeedback.class));
    }

    @Test
    void rejectsOutOfRangeItem() {
        UUID evaluationId = UUID.randomUUID();
        AiEvaluationSnapshotRepository snapshots = mock(AiEvaluationSnapshotRepository.class);
        AiEvaluationSnapshot snapshot = new AiEvaluationSnapshot();
        snapshot.setId(evaluationId);
        snapshot.setOutputJson("{\"strengths\":[],\"weaknesses\":[],\"suggestions\":[]}");
        when(snapshots.findById(evaluationId)).thenReturn(Optional.of(snapshot));
        AiEvaluationFeedbackService service = new AiEvaluationFeedbackService(
                snapshots, mock(AiEvaluationFeedbackRepository.class));

        assertThatThrownBy(() -> service.save(
                new AiEvaluationFeedbackService.FeedbackRequest(evaluationId, "strengths", 0, true)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
