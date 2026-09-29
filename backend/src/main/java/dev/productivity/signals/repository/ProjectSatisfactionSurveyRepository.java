package dev.productivity.signals.repository;

import dev.productivity.signals.entity.ProjectSatisfactionSurvey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ProjectSatisfactionSurveyRepository extends JpaRepository<ProjectSatisfactionSurvey, Long> {
    List<ProjectSatisfactionSurvey> findByProjectIdOrderBySurveyDateDescCreatedAtDesc(String projectId);

    List<ProjectSatisfactionSurvey> findByProjectIdAndUserCodeIgnoreCaseOrderBySurveyDateDescCreatedAtDesc(
            String projectId,
            String userCode);

    List<ProjectSatisfactionSurvey> findByProjectIdAndSurveyDateBetweenOrderBySurveyDateDescCreatedAtDesc(
            String projectId,
            LocalDate start,
            LocalDate end);

    List<ProjectSatisfactionSurvey> findByProjectIdAndUserCodeIgnoreCaseAndSurveyDateBetweenOrderBySurveyDateDescCreatedAtDesc(
            String projectId,
            String userCode,
            LocalDate start,
            LocalDate end);

    @Query("""
            SELECT s FROM ProjectSatisfactionSurvey s
            WHERE s.projectId = :projectId
              AND (
                (s.periodStart IS NOT NULL AND s.periodEnd IS NOT NULL
                  AND s.periodStart <= :end AND s.periodEnd >= :start)
                OR ((s.periodStart IS NULL OR s.periodEnd IS NULL)
                  AND s.surveyDate BETWEEN :start AND :end)
              )
            ORDER BY s.surveyDate DESC, s.createdAt DESC
            """)
    List<ProjectSatisfactionSurvey> findByProjectIdAndEffectivePeriodOverlap(
            @Param("projectId") String projectId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("""
            SELECT s FROM ProjectSatisfactionSurvey s
            WHERE s.projectId = :projectId
              AND LOWER(s.userCode) = LOWER(:userCode)
              AND (
                (s.periodStart IS NOT NULL AND s.periodEnd IS NOT NULL
                  AND s.periodStart <= :end AND s.periodEnd >= :start)
                OR ((s.periodStart IS NULL OR s.periodEnd IS NULL)
                  AND s.surveyDate BETWEEN :start AND :end)
              )
            ORDER BY s.surveyDate DESC, s.createdAt DESC
            """)
    List<ProjectSatisfactionSurvey> findByProjectIdAndUserCodeAndEffectivePeriodOverlap(
            @Param("projectId") String projectId,
            @Param("userCode") String userCode,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);
}
