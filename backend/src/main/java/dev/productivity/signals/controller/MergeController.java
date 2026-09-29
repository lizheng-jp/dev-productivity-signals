package dev.productivity.signals.controller;

import dev.productivity.signals.dto.MergeRequestActivityPeriodDTO;
import dev.productivity.signals.dto.MergeRequestStatsDTO;
import dev.productivity.signals.service.MergeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "マージリクエスト情報関連")
@RequestMapping("/api/projects/{projectId}/merge_requests")
public class MergeController {

    private final MergeService mergeService;

    public MergeController(MergeService mergeService) {
        this.mergeService = mergeService;
    }

    @Operation(summary = "マージリクエスト情報を取得します")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "正常終了"),
            @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping("/stats")
    public ResponseEntity<MergeRequestStatsDTO> getMergeRequestStats(
            @PathVariable String projectId,
            @RequestParam String since,
            @RequestParam String until,
            @Parameter(description = "氏名コード") @RequestParam(required = false) String userName,
            @RequestParam(required = false) String refName) {

        MergeRequestStatsDTO stats = mergeService.getMergeRequestStatsForUser(projectId, userName, since, until,
                refName);
        return ResponseEntity.ok(stats);
    }

    @Operation(summary = "プロジェクトのマージリクエスト活動期間を取得します")
    @GetMapping("/activity-period")
    public ResponseEntity<MergeRequestActivityPeriodDTO> getMergeRequestActivityPeriod(
            @PathVariable String projectId,
            @RequestParam(required = false) String refName,
            @Parameter(description = "氏名コード") @RequestParam(required = false) String userName) {

        return ResponseEntity.ok(mergeService.getMergeRequestActivityPeriod(projectId, refName, userName));
    }
}
