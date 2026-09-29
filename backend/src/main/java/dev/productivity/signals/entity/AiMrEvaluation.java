package dev.productivity.signals.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_mr_evaluation")
public class AiMrEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "mr_iid", nullable = false)
    private Integer mrIid;

    @Column(name = "author_username")
    private String authorUsername;

    @Column(name = "target_branch")
    private String targetBranch;

    @Column(name = "source_branch")
    private String sourceBranch;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "prompt_version_id")
    private Long promptVersionId;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_hash")
    private String promptHash;

    @Column(name = "change_type")
    private String changeType;

    @Column(name = "type_coefficient")
    private Double typeCoefficient;

    @Column(name = "complexity")
    private String complexity;

    @Column(name = "complexity_coefficient")
    private Double complexityCoefficient;

    @Column(name = "maintainability")
    private String maintainability;

    @Column(name = "maintainability_coefficient")
    private Double maintainabilityCoefficient;

    @Column(name = "has_bug")
    private Boolean hasBug;

    @Column(name = "bug_coefficient")
    private Double bugCoefficient;

    @Column(name = "contribution")
    private String contribution;

    @Column(name = "contribution_coefficient")
    private Double contributionCoefficient;

    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;
}
