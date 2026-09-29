package dev.productivity.signals.controller;

import dev.productivity.signals.dto.CommitCountResponseDTO;
import dev.productivity.signals.dto.CommitMetricsResponseDTO;
import dev.productivity.signals.service.CommitService;
import dev.productivity.signals.service.DemoMockDataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import java.util.List;

/**
 * コミット統計情報取得APIのコントローラー
 * <p>
 * GitLabプロジェクトID・期間・著者リストを指定して、コミット数を取得します。
 */
@RestController
@Tag(name = "コミット関連")
@RequestMapping("/api/gitlab/projects/{projectId}")
@RequiredArgsConstructor
public class CommitController {

    private final CommitService commitService;
    private final DemoMockDataService demoMockDataService;

    /**
     * 指定したGitLabプロジェクト・期間・著者のコミット数を取得します。
     *
     * @param projectId GitLabのプロジェクトID
     * @param since     検索開始日
     * @param until     検索終了日
     * @param authors   コミット著者（複数指定可。未指定時は全著者）
     * @return コミット統計情報DTO
     */
    @Operation(summary = "コミット数を取得します")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "正常終了"),
            @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping("/commits/count")
    public CommitMetricsResponseDTO getCommitCount(
            @PathVariable String projectId,
            @RequestParam(required = false, defaultValue = "") String since,
            @RequestParam(required = false, defaultValue = "") String until,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String refName) {
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getCommitMetrics(projectId, author, since, until);
        }
        return commitService.getCommitCount(projectId, author, since, until, refName);
    }
}
