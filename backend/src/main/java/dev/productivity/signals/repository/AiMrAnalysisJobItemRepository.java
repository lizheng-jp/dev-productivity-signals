package dev.productivity.signals.repository;

import dev.productivity.signals.entity.AiMrAnalysisJobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiMrAnalysisJobItemRepository extends JpaRepository<AiMrAnalysisJobItem, Long> {
    List<AiMrAnalysisJobItem> findByJobIdOrderByIdAsc(Long jobId);
}
