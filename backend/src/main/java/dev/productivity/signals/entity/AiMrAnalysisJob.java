package dev.productivity.signals.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_mr_analysis_job")
public class AiMrAnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "ref_name")
    private String refName;

    @Column(name = "since_date", nullable = false)
    private LocalDate sinceDate;

    @Column(name = "until_date", nullable = false)
    private LocalDate untilDate;

    @Column(name = "prompt_version_id", nullable = false)
    private Long promptVersionId;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    @Column(name = "analyzed_count", nullable = false)
    private Integer analyzedCount;

    @Column(name = "skipped_count", nullable = false)
    private Integer skippedCount;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
