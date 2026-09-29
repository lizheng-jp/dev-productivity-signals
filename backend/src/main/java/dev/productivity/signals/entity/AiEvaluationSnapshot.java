package dev.productivity.signals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_evaluation_snapshot")
@Getter
@Setter
public class AiEvaluationSnapshot {
    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String selectionJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String outputJson;

    @Column(nullable = false)
    private Instant createdAt;
}
