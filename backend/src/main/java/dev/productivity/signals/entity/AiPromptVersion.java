package dev.productivity.signals.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_prompt_version")
public class AiPromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_key", nullable = false, unique = true)
    private String versionKey;

    @Column(name = "name")
    private String name;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    private String promptText;

    @Column(name = "prompt_hash", nullable = false)
    private String promptHash;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
