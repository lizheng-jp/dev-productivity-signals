package dev.productivity.signals.controller;

import dev.productivity.signals.dto.ProjectSatisfactionSummaryDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSurveyRequestDTO;
import dev.productivity.signals.dto.ProjectSatisfactionSurveyResponseDTO;
import dev.productivity.signals.service.DemoMockDataService;
import dev.productivity.signals.service.ProjectSatisfactionSurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "プロジェクト満足度調査")
@RequestMapping("/api/projects/{projectId}/satisfaction-surveys")
@RequiredArgsConstructor
public class ProjectSatisfactionSurveyController {

    private final ProjectSatisfactionSurveyService service;
    private final DemoMockDataService demoMockDataService;

    @Operation(summary = "プロジェクト満足度調査の回答一覧を取得します")
    @GetMapping
    public List<ProjectSatisfactionSurveyResponseDTO> getSurveys(
            @PathVariable String projectId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String userName) {
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getSurveys(projectId, userName);
        }
        return service.getSurveys(projectId, since, until, userName);
    }

    @Operation(summary = "プロジェクト満足度調査の集計を取得します")
    @GetMapping("/summary")
    public ProjectSatisfactionSummaryDTO getSummary(
            @PathVariable String projectId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String userName) {
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getSatisfactionSummary(projectId, userName);
        }
        return service.getSummary(projectId, since, until, userName);
    }

    @Operation(summary = "プロジェクト満足度調査の回答を登録します")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectSatisfactionSurveyResponseDTO createSurvey(
            @PathVariable String projectId,
            @RequestBody ProjectSatisfactionSurveyRequestDTO dto) {
        return service.createSurvey(projectId, dto);
    }
}
