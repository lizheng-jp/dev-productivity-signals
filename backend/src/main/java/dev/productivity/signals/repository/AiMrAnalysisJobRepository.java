package dev.productivity.signals.repository;

import dev.productivity.signals.entity.AiMrAnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiMrAnalysisJobRepository extends JpaRepository<AiMrAnalysisJob, Long> {
    List<AiMrAnalysisJob> findTop50ByOrderByRequestedAtDesc();
}
