package dev.productivity.signals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ComparisonAnalysisResponseDTO {
    private Map<String, Object> spaceMetrics;
    private AiEvaluationResponseDTO aiEvaluation;
}
