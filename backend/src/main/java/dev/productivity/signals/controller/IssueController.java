package dev.productivity.signals.controller;

import dev.productivity.signals.dto.IssueStatsDTO;
import dev.productivity.signals.service.IssueService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * GitLabプロジェクトのイシュー統計情報を取得するAPIコントローラー
 * <p>
 * プロジェクトID、期間（開始・終了日）を指定して、
 * 作成・クローズ・バグ数の統計情報を取得します。
 */
@RestController
@Tag(name = "イシュー関連")
@RequestMapping("/api/gitlab/projects/{projectId}/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    /**
     * 指定したGitLabプロジェクト・期間内のイシュー統計情報を取得します。
     *
     * @param projectId GitLabのプロジェクトID
     * @param since     検索期間の開始日
     * @param until     検索期間の終了日
     * @param assigneeUsername 担当者のユーザー名
     * @param authorUsername   作成者のユーザー名
     * @return イシューの作成数・クローズ数・バグ数を含む統計情報DTO
     */
    @Operation(summary = "イシュー数を取得します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping("/stats")
    public IssueStatsDTO getIssueStats(
            @PathVariable String projectId,
            @RequestParam(required = false, defaultValue = "") String since,
            @RequestParam(required = false, defaultValue = "") String until,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String refName) {
        return issueService.getIssueStats(projectId, since, until, userName, refName);
    }
}
