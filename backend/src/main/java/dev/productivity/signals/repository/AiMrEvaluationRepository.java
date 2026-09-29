package dev.productivity.signals.repository;

import dev.productivity.signals.entity.AiMrEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiMrEvaluationRepository extends JpaRepository<AiMrEvaluation, Long> {
    boolean existsByProjectIdAndMrIid(String projectId, Integer mrIid);

    boolean existsByProjectIdAndMrIidAndPromptVersionId(String projectId, Integer mrIid, Long promptVersionId);

    Optional<AiMrEvaluation> findFirstByProjectIdAndMrIid(String projectId, Integer mrIid);

    Optional<AiMrEvaluation> findFirstByProjectIdAndMrIidOrderByAnalyzedAtDesc(
            String projectId, Integer mrIid);

    Optional<AiMrEvaluation> findFirstByProjectIdAndMrIidAndPromptVersionId(
            String projectId,
            Integer mrIid,
            Long promptVersionId);

    Optional<AiMrEvaluation> findFirstByProjectIdAndMrIidAndPromptVersionIdIsNull(String projectId, Integer mrIid);

    List<AiMrEvaluation> findByProjectIdAndMergedAtBetween(
            String projectId,
            LocalDateTime start,
            LocalDateTime end);

    @Query("""
            select e from AiMrEvaluation e
            where e.projectId = :projectId
              and (e.targetBranch = :branchName or e.sourceBranch = :branchName)
              and e.mergedAt between :start and :end
            """)
    List<AiMrEvaluation> findByProjectIdAndBranchAndMergedAtBetween(
            String projectId, String branchName, LocalDateTime start, LocalDateTime end);

    List<AiMrEvaluation> findByProjectIdAndAuthorUsernameAndMergedAtBetween(
            String projectId,
            String authorUsername,
            LocalDateTime start,
            LocalDateTime end);

    @Query("""
            select e from AiMrEvaluation e
            where e.projectId = :projectId
              and e.authorUsername = :authorUsername
              and (e.targetBranch = :branchName or e.sourceBranch = :branchName)
              and e.mergedAt between :start and :end
            """)
    List<AiMrEvaluation> findByProjectIdAndAuthorUsernameAndBranchAndMergedAtBetween(
            String projectId, String authorUsername, String branchName, LocalDateTime start, LocalDateTime end);
}
