package dev.productivity.signals.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;

import dev.productivity.signals.dto.ProjectDTO;
import dev.productivity.signals.dto.BranchDTO;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class GitService {

    private final RestTemplate rt;
    private final GitHubRepositoryService gitHubRepositoryService;

    @Value("${gitlab.api.url}")
    private String base;

    @Value("${gitlab.api.token}")
    private String token;

    @Value("${gitlab.cache.project-members.ttl-minutes:1440}")
    private long projectMembersCacheTtlMinutes;

    @Value("${gitlab.cache.branches.ttl-minutes:1440}")
    private long branchesCacheTtlMinutes;

    @Value("${gitlab.cache.projects.ttl-minutes:1440}")
    private long projectsCacheTtlMinutes;

    private volatile CacheEntry<List<ProjectDTO>> projectsCache;
    private final AtomicBoolean projectsRefreshInProgress = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, CacheEntry<List<String>>> projectMembersCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<BranchDTO>>> branchesCache = new ConcurrentHashMap<>();

    public GitService(RestTemplate rt, GitHubRepositoryService gitHubRepositoryService) {
        this.rt = rt;
        this.gitHubRepositoryService = gitHubRepositoryService;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("PRIVATE-TOKEN", token);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    /**
     * GitLabからすべてのプロジェクトリストを取得する
     *
     * @return List<ProjectDTO> プロジェクトのリスト
     */
    public synchronized List<ProjectDTO> getAllProjects() {
        CacheEntry<List<ProjectDTO>> cached = projectsCache;
        if (isCacheValid(cached)) {
            return new ArrayList<>(cached.value());
        }

        if (cached != null && !cached.value().isEmpty()) {
            refreshProjectsInBackground();
            return new ArrayList<>(cached.value());
        }

        List<ProjectDTO> projects = loadAllProjects();
        updateProjectsCache(projects);
        return projects;
    }

    private void refreshProjectsInBackground() {
        if (!projectsRefreshInProgress.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                List<ProjectDTO> refreshedProjects = loadAllProjects();
                updateProjectsCache(refreshedProjects);
            } finally {
                projectsRefreshInProgress.set(false);
            }
        });
    }

    private List<ProjectDTO> loadAllProjects() {
        List<ProjectDTO> allProjects = new ArrayList<>();
        int page = 1;
        int perPage = 100; // 1ページあたりのアイテム数 (GitLabのデフォルトかつ最大値)

        try {
            while (true) {
                String url = String.format(
                        "%s/projects?per_page=%d&page=%d&order_by=id&sort=asc",
                        base,
                        perPage,
                        page);

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));

                // レスポンスをProjectDTOの配列として受け取る
                ResponseEntity<ProjectDTO[]> res = rt.exchange(req, ProjectDTO[].class);

                ProjectDTO[] projects = res.getBody();

                // レスポンスがnullまたは空配列ならループを終了
                if (projects == null || projects.length == 0) {
                    break;
                }

                Arrays.stream(projects).forEach(project -> project.setProvider("gitlab"));
                allProjects.addAll(Arrays.asList(projects));

                // 取得したアイテム数がperPageより少なければ、それが最終ページ
                if (projects.length < perPage) {
                    break;
                }

                page++;
            }
        } catch (Exception e) {
            log.warn("Failed to refresh GitLab projects; retaining the last successful cache", e);
            return List.of();
        }
        return allProjects;
    }

    private void updateProjectsCache(List<ProjectDTO> projects) {
        if (projects == null || projects.isEmpty()) {
            return;
        }
        projectsCache = new CacheEntry<>(
                List.copyOf(projects),
                Instant.now().plus(Duration.ofMinutes(projectsCacheTtlMinutes)));
    }

    /**
     * GitLabから指定されたプロジェクトのすべてのメンバーのユーザー名（実際は氏名コード）を取得する
     *
     * @param projectId プロジェクトID
     * @return List<String> メンバーのユーザー名リスト
     */
    public List<String> getAllProjectUserCodes(String projectId) {
        if (gitHubRepositoryService.supports(projectId)) {
            return gitHubRepositoryService.getProjectMembers(projectId);
        }
        CacheEntry<List<String>> cached = projectMembersCache.get(projectId);
        if (isCacheValid(cached)) {
            return new ArrayList<>(cached.value());
        }

        List<String> allUsernames = new ArrayList<>();
        int page = 1;
        int perPage = 100;

        try {
            while (true) {
                String url = String.format(
                        "%s/projects/%s/members/all?per_page=%d&page=%d",
                        base,
                        projectId,
                        perPage,
                        page);

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));

                // レスポンスをMapの配列として受け取る
                ResponseEntity<List> res = rt.exchange(req, List.class);

                List<java.util.LinkedHashMap<String, Object>> members = res.getBody();

                // レスポンスがnullまたは空ならループを終了
                if (members == null || members.isEmpty()) {
                    break;
                }

                for (java.util.LinkedHashMap<String, Object> member : members) {
                    allUsernames.add((String) member.get("username"));
                }

                // 取得したアイテム数がperPageより少なければ、それが最終ページ
                if (members.size() < perPage) {
                    break;
                }

                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!allUsernames.isEmpty()) {
            projectMembersCache.put(projectId, new CacheEntry<>(
                    List.copyOf(allUsernames),
                    Instant.now().plus(Duration.ofMinutes(projectMembersCacheTtlMinutes))));
        }
        return allUsernames;
    }

    /**
     * GitLabから指定されたプロジェクトのブランチ一覧を取得する
     *
     * @param projectId プロジェクトID
     * @return List<BranchDTO> ブランチ情報のリスト
     */
    public List<BranchDTO> getBranches(String projectId) {
        if (gitHubRepositoryService.supports(projectId)) {
            return gitHubRepositoryService.getBranches(projectId);
        }
        CacheEntry<List<BranchDTO>> cached = branchesCache.get(projectId);
        if (isCacheValid(cached)) {
            return new ArrayList<>(cached.value());
        }

        List<BranchDTO> allBranches = new ArrayList<>();
        int page = 1;
        int perPage = 100;

        try {
            while (true) {
                String url = String.format(
                        "%s/projects/%s/repository/branches?per_page=%d&page=%d",
                        base,
                        projectId,
                        perPage,
                        page);

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));

                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray branchesJson = new JSONArray(res.getBody());

                if (branchesJson.isEmpty()) {
                    break;
                }

                for (int i = 0; i < branchesJson.length(); i++) {
                    JSONObject branchObj = branchesJson.getJSONObject(i);
                    BranchDTO dto = new BranchDTO();
                    dto.setName(branchObj.getString("name"));
                    dto.setMerged(branchObj.getBoolean("merged"));
                    dto.setIsProtected(branchObj.getBoolean("protected"));
                    dto.setIsDefault(branchObj.getBoolean("default"));
                    dto.setWebUrl(branchObj.getString("web_url"));

                    if (branchObj.has("commit") && !branchObj.isNull("commit")) {
                        JSONObject commit = branchObj.getJSONObject("commit");
                        dto.setLastCommitAt(commit.optString("committed_date"));
                    }

                    allBranches.add(dto);
                }

                if (branchesJson.length() < perPage) {
                    break;
                }

                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!allBranches.isEmpty()) {
            branchesCache.put(projectId, new CacheEntry<>(
                    List.copyOf(allBranches),
                    Instant.now().plus(Duration.ofMinutes(branchesCacheTtlMinutes))));
        }
        return allBranches;
    }

    private <T> boolean isCacheValid(CacheEntry<T> entry) {
        return entry != null && Instant.now().isBefore(entry.expiresAt());
    }

    private record CacheEntry<T>(T value, Instant expiresAt) {
    }
}
