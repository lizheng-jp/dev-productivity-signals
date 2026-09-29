package dev.productivity.signals.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_mr_analysis_job_item")
public class AiMrAnalysisJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "mr_iid", nullable = false)
    private Integer mrIid;

    @Column(name = "author_username")
    private String authorUsername;

    @Column(name = "source_branch")
    private String sourceBranch;

    @Column(name = "target_branch")
    private String targetBranch;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "evaluation_id")
    private Long evaluationId;

    @Column(name = "skip_reason", columnDefinition = "TEXT")
    private String skipReason;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "diff_line_count")
    private Integer diffLineCount;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
