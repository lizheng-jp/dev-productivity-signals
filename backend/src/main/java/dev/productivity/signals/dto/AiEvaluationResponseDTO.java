package dev.productivity.signals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiEvaluationResponseDTO {
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> suggestions;
    private String evaluationId;

    public AiEvaluationResponseDTO(List<String> strengths, List<String> weaknesses, List<String> suggestions) {
        this.strengths = strengths;
        this.weaknesses = weaknesses;
        this.suggestions = suggestions;
    }
}
