package dev.productivity.signals.repository;

import dev.productivity.signals.entity.AiEvaluationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AiEvaluationSnapshotRepository extends JpaRepository<AiEvaluationSnapshot, UUID> {
}
