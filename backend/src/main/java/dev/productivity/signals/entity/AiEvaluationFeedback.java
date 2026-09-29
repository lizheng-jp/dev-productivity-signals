package dev.productivity.signals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "ai_evaluation_feedback",
        uniqueConstraints = @UniqueConstraint(columnNames = {"evaluation_id", "section", "item_index"}))
@Getter
@Setter
public class AiEvaluationFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID evaluationId;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private int itemIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String itemText;

    @Column(nullable = false)
    private boolean helpful;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
