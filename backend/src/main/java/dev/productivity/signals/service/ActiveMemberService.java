package dev.productivity.signals.service;

import dev.productivity.signals.dto.ProjectMemberGroupDTO;
import dev.productivity.signals.entity.GroupManagement;
import dev.productivity.signals.entity.GroupUserManagement;
import dev.productivity.signals.util.PerformanceTimingLog;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ActiveMemberService {

    private final GitService gitService;
    private final GitHubRepositoryService gitHubRepositoryService;
    private final GroupService groupService;
    private final CommitService commitService;
    private final MergeService mergeService;

    public List<ProjectMemberGroupDTO> getActiveProjectMembers(
            String projectId,
            String since,
            String until,
            String refName) {
        List<String> allUserCodes = PerformanceTimingLog.time("gitlab.projectMembers",
                () -> gitService.getAllProjectUserCodes(projectId));
        PerformanceTimingLog.addCount("members.total", allUserCodes.size());

        Set<String> activeUserCodes = new HashSet<>();
        activeUserCodes.addAll(PerformanceTimingLog.time("gitlab.activeCommitUsers",
                () -> commitService.getActiveCommitUserCodes(projectId, allUserCodes, since, until, refName)));

        PerformanceTimingLog.time("gitlab.activeMergeUsers", () -> {
            addMrAuthors(activeUserCodes, mergeService.fetchAllMergedMergeRequests(projectId, since, until, refName));
        });

        List<ProjectMemberGroupDTO> activeMembers = allUserCodes.stream()
                .filter(userCode -> activeUserCodes.contains(normalizeUserCode(userCode)))
                .map(userCode -> toMemberGroupDto(projectId, userCode))
                .toList();
        PerformanceTimingLog.addCount("members.active", activeMembers.size());
        return activeMembers;
    }

    /**
     * Number of contributors with at least {@code minContributions} contributions in the period, at least 1.
     * A contribution count is the larger of a person's commits and merged pull requests rather than their sum,
     * because a squash-merged pull request also appears as one commit by the same author.
     */
    public int countCoreContributors(String projectId, String since, String until, String refName, int minContributions) {
        List<String> userCodes = gitService.getAllProjectUserCodes(projectId);
        Map<String, Long> commits = new HashMap<>();
        commitService.getCommitMetricsByUser(projectId, userCodes, since, until, refName)
                .forEach((userCode, metrics) -> commits.put(normalizeUserCode(userCode), (long) metrics.getTotalCommitCount()));

        Map<String, Long> merges = new HashMap<>();
        for (JSONObject mr : mergeService.fetchAllMergedMergeRequests(projectId, since, until, refName)) {
            JSONObject author = mr.optJSONObject("author");
            String username = author == null ? "" : author.optString("username", "");
            if (!username.isBlank()) {
                merges.merge(normalizeUserCode(username), 1L, Long::sum);
            }
        }

        long core = userCodes.stream()
                .map(this::normalizeUserCode)
                .distinct()
                .filter(userCode -> Math.max(commits.getOrDefault(userCode, 0L), merges.getOrDefault(userCode, 0L))
                        >= minContributions)
                .count();
        return (int) Math.max(1, core);
    }

    private void addMrAuthors(Set<String> activeUserCodes, List<JSONObject> mergeRequests) {
        for (JSONObject mr : mergeRequests) {
            JSONObject author = mr.optJSONObject("author");
            if (author == null) {
                continue;
            }
            String username = author.optString("username", "");
            if (!username.isBlank()) {
                activeUserCodes.add(normalizeUserCode(username));
            }
        }
    }

    private ProjectMemberGroupDTO toMemberGroupDto(String projectId, String userCode) {
        if (gitHubRepositoryService.supports(projectId)) {
            return new ProjectMemberGroupDTO(normalizeUserCode(userCode), null, null, null);
        }
        Optional<GroupUserManagement> groupUserOpt = groupService.findUserGroupInfoByUserCode(userCode);

        String userName = null;
        String groupId = null;
        String groupName = null;

        if (groupUserOpt.isPresent()) {
            GroupUserManagement groupUser = groupUserOpt.get();
            userName = groupUser.getUserName();
            groupId = groupUser.getId().getGroupId();

            Optional<GroupManagement> groupOpt = groupService.findGroupById(groupId);
            if (groupOpt.isPresent()) {
                groupName = groupOpt.get().getGroupName();
            }
        }

        return new ProjectMemberGroupDTO(normalizeUserCode(userCode), userName, groupId, groupName);
    }

    private String normalizeUserCode(String userCode) {
        return userCode == null ? "" : userCode.toUpperCase(Locale.ROOT);
    }
}
