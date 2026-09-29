package dev.productivity.signals.service;

import dev.productivity.signals.dto.ProjectSatisfactionSummaryDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSurveyRequestDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSurveyResponseDTO;
import dev.productivity.signals.entity.MetricWeightManagement;
import dev.productivity.signals.entity.ProjectSatisfactionSurvey;
import dev.productivity.signals.repository.MetricWeightManagementRepository;
import dev.productivity.signals.repository.ProjectSatisfactionSurveyRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static dev.productivity.signals.util.SpaceMetricConstants.*;

@Service
@RequiredArgsConstructor
public class ProjectSatisfactionSurveyService {

    private final ProjectSatisfactionSurveyRepository repository;
    private final MetricWeightManagementRepository metricWeightRepository;

    public List<ProjectSatisfactionSurveyResponseDTO> getSurveys(String projectId, String since, String until,
            String userCode) {
        return findSurveys(projectId, since, until, userCode).stream()
                .map(this::toResponse)
                .toList();
    }

    public ProjectSatisfactionSummaryDTO getSummary(String projectId, String since, String until, String userCode) {
        List<ProjectSatisfactionSurvey> surveys = findSurveys(projectId, since, until, userCode);
        String normalizedUserCode = blankToNull(userCode);
        if (surveys.isEmpty()) {
            return ProjectSatisfactionSummaryDTO.builder()
                    .projectId(projectId)
                    .userCode(normalizedUserCode)
                    .responseCount(0)
                    .build();
        }

        String summaryUserCode = normalizedUserCode != null ? normalizedUserCode : null;
        String summaryUserName = normalizedUserCode != null ? surveys.get(0).getUserName() : null;
        SatisfactionWeights weights = satisfactionWeights();
        return ProjectSatisfactionSummaryDTO.builder()
                .projectId(projectId)
                .userCode(summaryUserCode)
                .userName(summaryUserName)
                .responseCount(surveys.size())
                .satisfactionScore(round(surveys.stream().mapToDouble(survey -> calculateResponseScore(survey, weights)).average().orElse(0.0)))
                .s1JobSatisfaction(round(averageSubScore(surveys, SubScoreType.S1)))
                .s2DeveloperEfficacy(round(averageSubScore(surveys, SubScoreType.S2)))
                .s3Sustainability(round(averageSubScore(surveys, SubScoreType.S3)))
                .s4ImprovementPotential(round(averageSubScore(surveys, SubScoreType.S4)))
                .q1WorkValue(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ1WorkValue, false)))
                .q2WorkMeaning(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ2WorkMeaning, false)))
                .q3TeamSatisfaction(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ3TeamSatisfaction, false)))
                .q4RecommendTeam(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ4RecommendTeam, false)))
                .q5InformationAccess(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ5InformationAccess, false)))
                .q6EnvironmentSupport(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ6EnvironmentSupport, false)))
                .q7SupportFlow(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ7SupportFlow, false)))
                .q8OutcomeConfidence(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ8OutcomeConfidence, false)))
                .q9SustainableWorkload(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ9SustainableWorkload, false)))
                .q10Fatigue(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ10Fatigue, true)))
                .q11Detachment(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ11Detachment, true)))
                .q12Pressure(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ12Pressure, true)))
                .q13PsychologicalSafety(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ13PsychologicalSafety, false)))
                .q14ImprovementExpectation(round(averageQuestionScore(surveys, ProjectSatisfactionSurvey::getQ14ImprovementExpectation, false)))
                .build();
    }

    @Transactional
    public ProjectSatisfactionSurveyResponseDTO createSurvey(String projectId, ProjectSatisfactionSurveyRequestDTO dto) {
        validateScores(dto);
        String userCode = blankToNull(dto.getUserCode());
        if (userCode == null) {
            throw new IllegalArgumentException("userCode is required");
        }

        ProjectSatisfactionSurvey entity = new ProjectSatisfactionSurvey();
        entity.setProjectId(projectId);
        entity.setUserCode(userCode);
        entity.setUserName(blankToNull(dto.getUserName()));
        entity.setSurveyDate(dto.getSurveyDate() != null ? dto.getSurveyDate() : LocalDate.now());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setRespondentName(blankToNull(dto.getRespondentName()));
        entity.setQ1WorkValue(dto.getQ1WorkValue());
        entity.setQ2WorkMeaning(dto.getQ2WorkMeaning());
        entity.setQ3TeamSatisfaction(dto.getQ3TeamSatisfaction());
        entity.setQ4RecommendTeam(dto.getQ4RecommendTeam());
        entity.setQ5InformationAccess(dto.getQ5InformationAccess());
        entity.setQ6EnvironmentSupport(dto.getQ6EnvironmentSupport());
        entity.setQ7SupportFlow(dto.getQ7SupportFlow());
        entity.setQ8OutcomeConfidence(dto.getQ8OutcomeConfidence());
        entity.setQ9SustainableWorkload(dto.getQ9SustainableWorkload());
        entity.setQ10Fatigue(dto.getQ10Fatigue());
        entity.setQ11Detachment(dto.getQ11Detachment());
        entity.setQ12Pressure(dto.getQ12Pressure());
        entity.setQ13PsychologicalSafety(dto.getQ13PsychologicalSafety());
        entity.setQ14ImprovementExpectation(dto.getQ14ImprovementExpectation());
        entity.setComment(blankToNull(dto.getComment()));
        return toResponse(repository.save(entity));
    }

    private List<ProjectSatisfactionSurvey> findSurveys(String projectId, String since, String until, String userCode) {
        LocalDate start = parseDate(since);
        LocalDate end = parseDate(until);
        String normalizedUserCode = blankToNull(userCode);
        if (start != null || end != null) {
            LocalDate effectiveStart = start != null ? start : LocalDate.of(1970, 1, 1);
            LocalDate effectiveEnd = end != null ? end : LocalDate.now();
            if (normalizedUserCode != null) {
                return repository.findByProjectIdAndUserCodeAndEffectivePeriodOverlap(
                        projectId,
                        normalizedUserCode,
                        effectiveStart,
                        effectiveEnd);
            }
            return repository.findByProjectIdAndEffectivePeriodOverlap(
                    projectId,
                    effectiveStart,
                    effectiveEnd);
        }
        if (normalizedUserCode != null) {
            return repository.findByProjectIdAndUserCodeIgnoreCaseOrderBySurveyDateDescCreatedAtDesc(
                    projectId,
                    normalizedUserCode);
        }
        return repository.findByProjectIdOrderBySurveyDateDescCreatedAtDesc(projectId);
    }

    private ProjectSatisfactionSurveyResponseDTO toResponse(ProjectSatisfactionSurvey entity) {
        SatisfactionSubScores subScores = calculateSubScores(entity);
        SatisfactionWeights weights = satisfactionWeights();
        return new ProjectSatisfactionSurveyResponseDTO(
                entity.getId(),
                entity.getProjectId(),
                entity.getUserCode(),
                entity.getUserName(),
                entity.getSurveyDate(),
                entity.getPeriodStart(),
                entity.getPeriodEnd(),
                entity.getRespondentName(),
                entity.getQ1WorkValue(),
                entity.getQ2WorkMeaning(),
                entity.getQ3TeamSatisfaction(),
                entity.getQ4RecommendTeam(),
                entity.getQ5InformationAccess(),
                entity.getQ6EnvironmentSupport(),
                entity.getQ7SupportFlow(),
                entity.getQ8OutcomeConfidence(),
                entity.getQ9SustainableWorkload(),
                entity.getQ10Fatigue(),
                entity.getQ11Detachment(),
                entity.getQ12Pressure(),
                entity.getQ13PsychologicalSafety(),
                entity.getQ14ImprovementExpectation(),
                entity.getComment(),
                round(calculateResponseScore(entity, weights)),
                round(subScores.s1()),
                round(subScores.s2()),
                round(subScores.s3()),
                round(subScores.s4()),
                entity.getCreatedAt());
    }

    private void validateScores(ProjectSatisfactionSurveyRequestDTO dto) {
        validateScore("q1WorkValue", dto.getQ1WorkValue());
        validateScore("q2WorkMeaning", dto.getQ2WorkMeaning());
        validateScore("q3TeamSatisfaction", dto.getQ3TeamSatisfaction());
        validateScore("q4RecommendTeam", dto.getQ4RecommendTeam());
        validateScore("q5InformationAccess", dto.getQ5InformationAccess());
        validateScore("q6EnvironmentSupport", dto.getQ6EnvironmentSupport());
        validateScore("q7SupportFlow", dto.getQ7SupportFlow());
        validateScore("q8OutcomeConfidence", dto.getQ8OutcomeConfidence());
        validateScore("q9SustainableWorkload", dto.getQ9SustainableWorkload());
        validateScore("q10Fatigue", dto.getQ10Fatigue());
        validateScore("q11Detachment", dto.getQ11Detachment());
        validateScore("q12Pressure", dto.getQ12Pressure());
        validateScore("q13PsychologicalSafety", dto.getQ13PsychologicalSafety());
        validateScore("q14ImprovementExpectation", dto.getQ14ImprovementExpectation());
    }

    private void validateScore(String name, Integer value) {
        if (value == null || value < 1 || value > 5) {
            throw new IllegalArgumentException(name + " must be between 1 and 5");
        }
    }

    private double calculateResponseScore(ProjectSatisfactionSurvey survey, SatisfactionWeights weights) {
        SatisfactionSubScores scores = calculateSubScores(survey);
        double totalWeight = weights.s1() + weights.s2() + weights.s3() + weights.s4();
        if (totalWeight <= 0) {
            return averageScores(scores.s1(), scores.s2(), scores.s3(), scores.s4());
        }
        return (scores.s1() * weights.s1()
                + scores.s2() * weights.s2()
                + scores.s3() * weights.s3()
                + scores.s4() * weights.s4()) / totalWeight;
    }

    private Double averageSubScore(List<ProjectSatisfactionSurvey> surveys, SubScoreType type) {
        return surveys.stream()
                .map(this::calculateSubScores)
                .mapToDouble(scores -> switch (type) {
                    case S1 -> scores.s1();
                    case S2 -> scores.s2();
                    case S3 -> scores.s3();
                    case S4 -> scores.s4();
                })
                .average()
                .orElse(Double.NaN);
    }

    private Double averageQuestionScore(List<ProjectSatisfactionSurvey> surveys,
            Function<ProjectSatisfactionSurvey, Integer> extractor, boolean reverse) {
        return surveys.stream()
                .map(extractor)
                .mapToDouble(value -> reverse ? reverseQuestionScore(value) : positiveQuestionScore(value))
                .average()
                .orElse(Double.NaN);
    }

    private SatisfactionSubScores calculateSubScores(ProjectSatisfactionSurvey survey) {
        return new SatisfactionSubScores(
                averageScores(
                        positiveQuestionScore(survey.getQ1WorkValue()),
                        positiveQuestionScore(survey.getQ2WorkMeaning()),
                        positiveQuestionScore(survey.getQ3TeamSatisfaction()),
                        positiveQuestionScore(survey.getQ4RecommendTeam())),
                averageScores(
                        positiveQuestionScore(survey.getQ5InformationAccess()),
                        positiveQuestionScore(survey.getQ6EnvironmentSupport()),
                        positiveQuestionScore(survey.getQ7SupportFlow()),
                        positiveQuestionScore(survey.getQ8OutcomeConfidence())),
                averageScores(
                        positiveQuestionScore(survey.getQ9SustainableWorkload()),
                        reverseQuestionScore(survey.getQ10Fatigue()),
                        reverseQuestionScore(survey.getQ11Detachment()),
                        reverseQuestionScore(survey.getQ12Pressure())),
                averageScores(
                        positiveQuestionScore(survey.getQ13PsychologicalSafety()),
                        positiveQuestionScore(survey.getQ14ImprovementExpectation())));
    }

    private double positiveQuestionScore(Integer value) {
        return normalizeLikertToScore(value);
    }

    private double reverseQuestionScore(Integer value) {
        return (5.0 - value) / 4.0 * 100.0;
    }

    private double averageScores(double... values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.length;
    }

    private enum SubScoreType {
        S1, S2, S3, S4
    }

    private record SatisfactionSubScores(double s1, double s2, double s3, double s4) {
    }

    private record SatisfactionWeights(double s1, double s2, double s3, double s4) {
    }

    private SatisfactionWeights satisfactionWeights() {
        Map<String, Double> weightsByMetric = new HashMap<>();
        for (MetricWeightManagement config : metricWeightRepository.findByIsActive(true)) {
            weightsByMetric.put(config.getMetricKey(), Math.max(0.0, config.getWeight()));
        }

        SatisfactionWeights weights = new SatisfactionWeights(
                weightsByMetric.getOrDefault(satisfactionJobMeaning, 0.0),
                weightsByMetric.getOrDefault(satisfactionDeveloperEfficacy, 0.0),
                weightsByMetric.getOrDefault(satisfactionSustainability, 0.0),
                weightsByMetric.getOrDefault(satisfactionImprovementPotential, 0.0));

        if (weights.s1() + weights.s2() + weights.s3() + weights.s4() <= 0) {
            return new SatisfactionWeights(1.0, 1.0, 1.0, 1.0);
        }
        return weights;
    }

    private double normalizeLikertToScore(double likertAverage) {
        return (likertAverage - 1.0) / 4.0 * 100.0;
    }

    private Double round(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
