package dev.productivity.signals.controller;

import dev.productivity.signals.dto.ProjectDTO;
import dev.productivity.signals.dto.BranchDTO;
import dev.productivity.signals.dto.ProjectMemberGroupDTO;
import dev.productivity.signals.entity.GroupManagement;
import dev.productivity.signals.entity.GroupUserManagement;
import dev.productivity.signals.service.ActiveMemberService;
import dev.productivity.signals.service.DemoMockDataService;
import dev.productivity.signals.service.GitService;
import dev.productivity.signals.service.GitHubRepositoryService;
import dev.productivity.signals.service.GroupService;
import dev.productivity.signals.util.PerformanceTimingLog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * プロジェクト情報取得APIのコントローラー
 * <p>
 * GitLabに存在するプロジェクト一覧を取得します。
 */
@RestController
@Tag(name = "プロジェクト情報関連")
@RequestMapping("/api/gitlab/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final GitService gitService;
    private final GitHubRepositoryService gitHubRepositoryService;
    private final GroupService groupService;
    private final ActiveMemberService activeMemberService;
    private final DemoMockDataService demoMockDataService;


    /**
     * GitLabプロジェクト一覧を取得します。
     * <p>
     * システムが管理している全てのGitLabプロジェクトの情報リストを返します。
     *
     * @return プロジェクト情報（ProjectDTO）のリスト
     */
    @Operation(summary = "プロジェクト一覧を取得します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping
    public List<ProjectDTO> getProjects() {
        if (demoMockDataService.isEnabled()) {
            return demoMockDataService.getProjects();
        }
        return gitService.getAllProjects();
    }

    /**
     * GitLabプロジェクトのメンバー一覧と所属グループを取得します。
     * <p>
     * 指定されたプロジェクトIDに所属する全てのユーザーのユーザー名と、
     * DBで紐付けられたグループの情報を返します。
     *
     * @param projectId プロジェクトID
     * @return メンバー情報（ユーザー名、グループID、グループ名）のリスト
     */
    @Operation(summary = "プロジェクトのメンバー一覧を取得します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping("/{projectId}/members")
    public List<ProjectMemberGroupDTO> getProjectMembers(@PathVariable String projectId) {
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getProjectMembers(projectId);
        }
        // ... (existing code)
        // 1. GitLabからプロジェクトのメンバーのuserCodeリストを取得 (GitLabのusernameがuserCodeに相当)
        List<String> gitLabUsernames = gitService.getAllProjectUserCodes(projectId);

        if (gitHubRepositoryService.supports(projectId)) {
            return gitLabUsernames.stream()
                    .map(username -> new ProjectMemberGroupDTO(username.toUpperCase(), null, null, null))
                    .collect(Collectors.toList());
        }

        // 2. 各userCodeに対応するグループ情報をDBから取得してDTOにマッピング
        return gitLabUsernames.stream().map(gitLabUsername -> {
            // GroupServiceでuserCodeを大文字変換してDB検索済み
            Optional<GroupUserManagement> groupUserOpt = groupService.findUserGroupInfoByUserCode(gitLabUsername);

            String userName = null;
            String groupId = null;
            String groupName = null;

            if (groupUserOpt.isPresent()) {
                GroupUserManagement groupUser = groupUserOpt.get();
                userName = groupUser.getUserName(); // DBのuser_name (氏名)
                groupId = groupUser.getId().getGroupId();

                Optional<GroupManagement> groupOpt = groupService.findGroupById(groupId);
                if (groupOpt.isPresent()) {
                    groupName = groupOpt.get().getGroupName();
                }
            }
            return new ProjectMemberGroupDTO(gitLabUsername.toUpperCase(), userName, groupId, groupName);
        }).collect(Collectors.toList());
    }

    @Operation(summary = "指定期間内に活動があるプロジェクトメンバー一覧を取得します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping("/{projectId}/active-members")
    public List<ProjectMemberGroupDTO> getActiveProjectMembers(
            @PathVariable String projectId,
            @RequestParam String since,
            @RequestParam String until,
            @RequestParam(required = false) String refName) {
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getActiveProjectMembers(projectId);
        }
        PerformanceTimingLog timing = PerformanceTimingLog.start(
                "project.activeMembers", projectId, "all-members", since, until, refName);
        try {
            return activeMemberService.getActiveProjectMembers(projectId, since, until, refName);
        } finally {
            timing.logSummary();
            PerformanceTimingLog.clear();
        }
    }

    /**
     * GitLabプロジェクトのブランチ一覧を取得します。
     *
     * @param projectId プロジェクトID
     * @return ブランチ情報（BranchDTO）のリスト
     */
    @Operation(summary = "プロジェクトのブランチ一覧を取得します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping("/{projectId}/repository/branches")
    public List<BranchDTO> getProjectBranches(@PathVariable String projectId) {
        if (demoMockDataService.shouldUseMockProject(projectId)) {
            return demoMockDataService.getBranches(projectId);
        }
        return gitService.getBranches(projectId);
    }
}
