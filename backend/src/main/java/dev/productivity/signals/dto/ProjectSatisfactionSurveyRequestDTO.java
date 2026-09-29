package dev.productivity.signals.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ProjectSatisfactionSurveyRequestDTO {
    private String userCode;
    private String userName;
    private LocalDate surveyDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String respondentName;
    private Integer q1WorkValue;
    private Integer q2WorkMeaning;
    private Integer q3TeamSatisfaction;
    private Integer q4RecommendTeam;
    private Integer q5InformationAccess;
    private Integer q6EnvironmentSupport;
    private Integer q7SupportFlow;
    private Integer q8OutcomeConfidence;
    private Integer q9SustainableWorkload;
    private Integer q10Fatigue;
    private Integer q11Detachment;
    private Integer q12Pressure;
    private Integer q13PsychologicalSafety;
    private Integer q14ImprovementExpectation;
    private String comment;
}
