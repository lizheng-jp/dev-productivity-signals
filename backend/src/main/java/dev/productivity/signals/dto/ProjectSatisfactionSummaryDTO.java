package dev.productivity.signals.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ProjectSatisfactionSummaryDTO {
    private String projectId;
    private String userCode;
    private String userName;
    private int responseCount;
    private Double satisfactionScore;
    private Double s1JobSatisfaction;
    private Double s2DeveloperEfficacy;
    private Double s3Sustainability;
    private Double s4ImprovementPotential;
    private Double q1WorkValue;
    private Double q2WorkMeaning;
    private Double q3TeamSatisfaction;
    private Double q4RecommendTeam;
    private Double q5InformationAccess;
    private Double q6EnvironmentSupport;
    private Double q7SupportFlow;
    private Double q8OutcomeConfidence;
    private Double q9SustainableWorkload;
    private Double q10Fatigue;
    private Double q11Detachment;
    private Double q12Pressure;
    private Double q13PsychologicalSafety;
    private Double q14ImprovementExpectation;
}
