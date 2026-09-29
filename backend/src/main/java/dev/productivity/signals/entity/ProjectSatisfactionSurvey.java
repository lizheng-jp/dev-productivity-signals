package dev.productivity.signals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "project_satisfaction_survey")
public class ProjectSatisfactionSurvey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "user_code", nullable = false)
    private String userCode;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "survey_date", nullable = false)
    private LocalDate surveyDate;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "respondent_name")
    private String respondentName;

    @Column(name = "q1_work_value", nullable = false)
    private Integer q1WorkValue;

    @Column(name = "q2_work_meaning", nullable = false)
    private Integer q2WorkMeaning;

    @Column(name = "q3_team_satisfaction", nullable = false)
    private Integer q3TeamSatisfaction;

    @Column(name = "q4_recommend_team", nullable = false)
    private Integer q4RecommendTeam;

    @Column(name = "q5_information_access", nullable = false)
    private Integer q5InformationAccess;

    @Column(name = "q6_environment_support", nullable = false)
    private Integer q6EnvironmentSupport;

    @Column(name = "q7_support_flow", nullable = false)
    private Integer q7SupportFlow;

    @Column(name = "q8_outcome_confidence", nullable = false)
    private Integer q8OutcomeConfidence;

    @Column(name = "q9_sustainable_workload", nullable = false)
    private Integer q9SustainableWorkload;

    @Column(name = "q10_fatigue", nullable = false)
    private Integer q10Fatigue;

    @Column(name = "q11_detachment", nullable = false)
    private Integer q11Detachment;

    @Column(name = "q12_pressure", nullable = false)
    private Integer q12Pressure;

    @Column(name = "q13_psychological_safety", nullable = false)
    private Integer q13PsychologicalSafety;

    @Column(name = "q14_improvement_expectation", nullable = false)
    private Integer q14ImprovementExpectation;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (surveyDate == null) {
            surveyDate = LocalDate.now();
        }
    }
}
