package dev.productivity.signals.service;

import dev.productivity.signals.dto.AiAnalysisProgressDTO;
import dev.productivity.signals.dto.AiMrAnalysisJobRequestDTO;
import dev.productivity.signals.entity.AiMrAnalysisJob;
import dev.productivity.signals.entity.AiMrAnalysisJobItem;
import dev.productivity.signals.entity.AiMrEvaluation;
import dev.productivity.signals.entity.AiPromptVersion;
import dev.productivity.signals.repository.AiMrAnalysisJobItemRepository;
import dev.productivity.signals.repository.AiMrAnalysisJobRepository;
import dev.productivity.signals.repository.AiMrEvaluationRepository;
import dev.productivity.signals.repository.AiPromptVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dev.productivity.signals.util.SpaceMetricConstants.*;
import dev.productivity.signals.util.PerformanceTimingLog;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiCorrectionService {

    private final GeminiService geminiService;
    private final MergeService mergeService;
    private final GitHubRepositoryService gitHubRepositoryService;
    private final AiMrEvaluationRepository aiMrEvaluationRepository;
    private final AiPromptVersionRepository aiPromptVersionRepository;
    private final AiMrAnalysisJobRepository aiMrAnalysisJobRepository;
    private final AiMrAnalysisJobItemRepository aiMrAnalysisJobItemRepository;
    private final RestTemplate rt;

    private final Map<String, AiAnalysisProgressDTO> analysisProgress = new ConcurrentHashMap<>();
    private static final String DEFAULT_PROMPT_VERSION_KEY = "mr-quality-default";

    @Value("${gitlab.api.url}")
    private String gitlabBase;

    @Value("${gitlab.api.token}")
    private String gitlabToken;

    // Coefficients from requirements
    private final Map<String, Double> typeCoefficients = Map.of(
            "New Feature", 1.1,
            "Bug Fix", 1.0,
            "Optimization", 1.3,
            "Auto-generated", 0.1,
            "Refactor", 1.5);

    private final Map<String, Double> complexityCoefficients = Map.of(
            "low", 0.7,
            "mid", 1.0,
            "high", 1.2);

    private final Map<String, Double> maintainabilityCoefficients = Map.of(
            "low", 0.7,
            "mid", 1.0,
            "high", 1.2);

    private final Map<String, Double> contributionCoefficients = Map.of(
            "low", 0.7,
            "mid", 1.0,
            "high", 1.2);

    public Map<String, Number> getCorrectedMetrics(List<AiMrEvaluation> evaluations,
            Map<String, Number> rawMetrics) {
        if (evaluations.isEmpty()) {
            return new HashMap<>(rawMetrics);
        }

        // Aggregate coefficients
        double totalC1 = 0; // Complexity
        double totalC2 = 0; // Type
        int count = evaluations.size();

        for (AiMrEvaluation eval : evaluations) {
            totalC1 += eval.getComplexityCoefficient();
            totalC2 += eval.getTypeCoefficient();
        }

        double avgC1 = totalC1 / count;
        double avgC2 = totalC2 / count;

        Map<String, Number> correctedMetrics = new HashMap<>(rawMetrics);

        // Apply correction formulas
        // mergedCount: mergedCount × C1 × C2
        if (correctedMetrics.containsKey(mergedCount)) {
            correctedMetrics.put(mergedCount, rawMetrics.get(mergedCount).doubleValue() * avgC1 * avgC2);
        }
        // mergedLeadTimeDays: mergedLeadTimeDays / C1
        if (correctedMetrics.containsKey(mergedLeadTimeHours)) {
            correctedMetrics.put(mergedLeadTimeHours, rawMetrics.get(mergedLeadTimeHours).doubleValue() / avgC1);
        }
        // bugsCausedCount: bugsCausedCount / C1
        if (correctedMetrics.containsKey(bugCausedCount)) {
            correctedMetrics.put(bugCausedCount, rawMetrics.get(bugCausedCount).doubleValue() / avgC1);
        }
        // bugFixLeadTimeDays: bugFixLeadTimeDays / C1
        if (correctedMetrics.containsKey(bugFixLeadTimeHours)) {
            correctedMetrics.put(bugFixLeadTimeHours, rawMetrics.get(bugFixLeadTimeHours).doubleValue() / avgC1);
        }
        // commitCount: commitCount × C2
        if (correctedMetrics.containsKey(commitCount)) {
            correctedMetrics.put(commitCount, rawMetrics.get(commitCount).doubleValue() * avgC2);
        }
        // linesTotal: linesTotal × C1 × C2
        if (correctedMetrics.containsKey(linesTotal)) {
            correctedMetrics.put(linesTotal, rawMetrics.get(linesTotal).doubleValue() * avgC1 * avgC2);
        }
        // reviewedCount: reviewedCount × C1
        if (correctedMetrics.containsKey(reviewedCount)) {
            correctedMetrics.put(reviewedCount, rawMetrics.get(reviewedCount).doubleValue() * avgC1);
        }
        // reviewWaitTime: reviewWaitTime / C1
        if (correctedMetrics.containsKey(reviewWaitTime)) {
            correctedMetrics.put(reviewWaitTime, rawMetrics.get(reviewWaitTime).doubleValue() / avgC1);
        }

        return correctedMetrics;
    }

    public List<AiMrEvaluation> getOrAnalyzeMRs(String projectId, String since, String until, String userName,
            String refName) {
        List<JSONObject> mergedMRs = PerformanceTimingLog.time("gitlab.aiFetchMergedMRs",
                () -> fetchMergedMRs(projectId, since, until, refName));
        PerformanceTimingLog.addCount("ai.mergedMRs", mergedMRs.size());

        List<AnalysisTarget> analysisTargets = PerformanceTimingLog.time("gitlab.aiBuildAnalysisTargets",
                () -> buildAnalysisTargets(projectId, mergedMRs, userName));
        PerformanceTimingLog.addCount("ai.analysisTargets", analysisTargets.size());

        List<AiMrEvaluation> results = new ArrayList<>();
        String progressKey = progressKey(projectId, since, until, userName, refName);
        updateProgress(progressKey, new AiAnalysisProgressDTO(
                analysisTargets.size(),
                analysisTargets.isEmpty() ? 0 : 1,
                0,
                analysisTargets.isEmpty() ? null : analysisTargets.get(0).mrJson().optInt("iid"),
                analysisTargets.isEmpty() ? "completed" : "running",
                analysisTargets.isEmpty() ? "分析対象のコード差分はありません" : "コード差分をAI分析中です"));

        PerformanceTimingLog.time("ai.diffAnalysisTotal", () -> {
            for (int index = 0; index < analysisTargets.size(); index++) {
                AnalysisTarget target = analysisTargets.get(index);
                JSONObject mrJson = target.mrJson();
                updateProgress(progressKey, new AiAnalysisProgressDTO(
                        analysisTargets.size(),
                        index + 1,
                        index,
                        mrJson.optInt("iid"),
                        "running",
                        "コード差分をAI分析中です"));
                AiMrEvaluation eval = analyzeAndSaveMR(projectId, target, userName);
                if (eval != null) {
                    results.add(eval);
                }
                updateProgress(progressKey, new AiAnalysisProgressDTO(
                        analysisTargets.size(),
                        index + 1,
                        index + 1,
                        mrJson.optInt("iid"),
                        index + 1 == analysisTargets.size() ? "completed" : "running",
                        index + 1 == analysisTargets.size() ? "コード差分のAI分析が完了しました" : "コード差分をAI分析中です"));
            }
        });
        PerformanceTimingLog.addCount("ai.evaluations", results.size());
        return results;
    }

    public Map<String, Object> analyzeMissingMergedMRs(String projectId, String since, String until, String refName) {
        AiMrAnalysisJobRequestDTO request = new AiMrAnalysisJobRequestDTO();
        request.setProjectId(projectId);
        request.setRefName(refName);
        request.setSinceDate(LocalDate.parse(since));
        request.setUntilDate(LocalDate.parse(until));

        AiMrAnalysisJob job = runAnalysisJob(request);
        List<AiMrAnalysisJobItem> items = aiMrAnalysisJobItemRepository.findByJobIdOrderByIdAsc(job.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("since", since);
        result.put("until", until);
        result.put("refName", refName);
        result.put("jobId", job.getId());
        result.put("targetCount", job.getTargetCount());
        result.put("analyzedCount", job.getAnalyzedCount());
        result.put("skippedCount", job.getSkippedCount());
        result.put("failedCount", job.getFailedCount());
        result.put("analyzedMrIids", itemIidsByStatus(items, "analyzed"));
        result.put("skippedMrIids", itemIidsByStatus(items, "skipped"));
        result.put("failedMrIids", itemIidsByStatus(items, "failed"));
        return result;
    }

    public List<AiPromptVersion> getPromptVersions() {
        return aiPromptVersionRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<AiMrAnalysisJob> getAnalysisJobs() {
        return aiMrAnalysisJobRepository.findTop50ByOrderByRequestedAtDesc();
    }

    public List<AiMrAnalysisJobItem> getAnalysisJobItems(Long jobId) {
        return aiMrAnalysisJobItemRepository.findByJobIdOrderByIdAsc(jobId);
    }

    public AiMrAnalysisJob runAnalysisJob(AiMrAnalysisJobRequestDTO request) {
        validateAnalysisJobRequest(request);
        AiPromptVersion promptVersion = resolvePromptVersion(request.getPromptVersionId());

        AiMrAnalysisJob job = new AiMrAnalysisJob();
        job.setProjectId(request.getProjectId());
        job.setRefName(blankToNull(request.getRefName()));
        job.setSinceDate(request.getSinceDate());
        job.setUntilDate(request.getUntilDate());
        job.setPromptVersionId(promptVersion.getId());
        job.setModel(promptVersion.getModel());
        job.setStatus("running");
        job.setTargetCount(0);
        job.setAnalyzedCount(0);
        job.setSkippedCount(0);
        job.setFailedCount(0);
        job.setRequestedBy(blankToNull(request.getRequestedBy()));
        job.setRequestedAt(LocalDateTime.now());
        job.setStartedAt(LocalDateTime.now());
        job = aiMrAnalysisJobRepository.save(job);

        int analyzed = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<JSONObject> mergedMRs = PerformanceTimingLog.time("gitlab.batchFetchMergedMRs",
                    () -> fetchMergedMRs(
                            request.getProjectId(),
                            request.getSinceDate().toString(),
                            request.getUntilDate().toString(),
                            request.getRefName()));
            job.setTargetCount(mergedMRs.size());
            aiMrAnalysisJobRepository.save(job);

            for (JSONObject mr : mergedMRs) {
                AiMrAnalysisJobItem item = createJobItem(job, mr);
                item = aiMrAnalysisJobItemRepository.save(item);
                int iid = item.getMrIid() == null ? 0 : item.getMrIid();
                if (iid <= 0) {
                    failed++;
                    finishItem(item, "failed", null, "MR IID is missing");
                    continue;
                }

                Optional<AiMrEvaluation> reusableEvaluation = findReusableEvaluation(
                        request.getProjectId(),
                        iid,
                        promptVersion);
                if (reusableEvaluation.isPresent()) {
                    skipped++;
                    item.setEvaluationId(reusableEvaluation.get().getId());
                    item.setSkipReason("already analyzed for this prompt version");
                    finishItem(item, "skipped", null, null);
                    continue;
                }

                MergeService.MergeRequestDiff diffResult = mergeService.getMergeRequestDiff(request.getProjectId(), iid);
                item.setDiffLineCount(diffResult.lineCount);
                if (diffResult.diff == null || diffResult.diff.isBlank()) {
                    failed++;
                    finishItem(item, "failed", null, "empty diff");
                    continue;
                }

                AiMrEvaluation evaluation = analyzeAndSaveMR(
                        request.getProjectId(),
                        new AnalysisTarget(mr, diffResult.diff, diffResult.lineCount),
                        null,
                        job,
                        promptVersion);
                if (evaluation == null) {
                    failed++;
                    finishItem(item, "failed", null, "AI analysis failed");
                } else {
                    analyzed++;
                    finishItem(item, "analyzed", evaluation.getId(), null);
                }
            }

            job.setStatus(failed > 0 ? "completed_with_errors" : "completed");
        } catch (Exception e) {
            job.setStatus("failed");
            job.setErrorMessage(e.getMessage());
            log.error("AI MR analysis job failed. jobId={}", job.getId(), e);
        } finally {
            job.setAnalyzedCount(analyzed);
            job.setSkippedCount(skipped);
            job.setFailedCount(failed);
            job.setFinishedAt(LocalDateTime.now());
            aiMrAnalysisJobRepository.save(job);
        }
        return job;
    }

    private void validateAnalysisJobRequest(AiMrAnalysisJobRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (request.getSinceDate() == null || request.getUntilDate() == null) {
            throw new IllegalArgumentException("sinceDate and untilDate are required");
        }
        if (request.getSinceDate().isAfter(request.getUntilDate())) {
            throw new IllegalArgumentException("sinceDate must be before or equal to untilDate");
        }
    }

    private AiPromptVersion resolvePromptVersion(Long requestedPromptVersionId) {
        if (requestedPromptVersionId != null) {
            return aiPromptVersionRepository.findById(requestedPromptVersionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "promptVersionId not found: " + requestedPromptVersionId));
        }

        return aiPromptVersionRepository.findFirstByActiveTrueOrderByCreatedAtDesc()
                .or(() -> aiPromptVersionRepository.findByVersionKey(DEFAULT_PROMPT_VERSION_KEY))
                .orElseGet(this::createDefaultPromptVersion);
    }

    private AiPromptVersion createDefaultPromptVersion() {
        String promptText = geminiService.diffAnalysisPromptTemplate();
        AiPromptVersion promptVersion = new AiPromptVersion();
        promptVersion.setVersionKey(DEFAULT_PROMPT_VERSION_KEY);
        promptVersion.setName("Default MR quality prompt");
        promptVersion.setModel(geminiService.currentModel());
        promptVersion.setPromptText(promptText);
        promptVersion.setPromptHash(sha256(promptText));
        promptVersion.setActive(true);
        promptVersion.setCreatedBy("system");
        promptVersion.setCreatedAt(LocalDateTime.now());
        return aiPromptVersionRepository.save(promptVersion);
    }

    private Optional<AiMrEvaluation> findReusableEvaluation(
            String projectId,
            int mrIid,
            AiPromptVersion promptVersion) {
        Optional<AiMrEvaluation> exactMatch = aiMrEvaluationRepository
                .findFirstByProjectIdAndMrIidAndPromptVersionId(projectId, mrIid, promptVersion.getId());
        if (exactMatch.isPresent()) {
            return exactMatch;
        }

        if (!DEFAULT_PROMPT_VERSION_KEY.equals(promptVersion.getVersionKey())) {
            return Optional.empty();
        }

        return aiMrEvaluationRepository.findFirstByProjectIdAndMrIidAndPromptVersionIdIsNull(projectId, mrIid)
                .map(evaluation -> {
                    evaluation.setPromptVersionId(promptVersion.getId());
                    evaluation.setModel(promptVersion.getModel());
                    evaluation.setPromptHash(promptVersion.getPromptHash());
                    return aiMrEvaluationRepository.save(evaluation);
                });
    }

    private AiMrAnalysisJobItem createJobItem(AiMrAnalysisJob job, JSONObject mr) {
        AiMrAnalysisJobItem item = new AiMrAnalysisJobItem();
        item.setJobId(job.getId());
        item.setProjectId(job.getProjectId());
        item.setMrIid(mr.optInt("iid", 0));
        JSONObject authorJson = mr.optJSONObject("author");
        item.setAuthorUsername(authorJson != null ? authorJson.optString("username", "") : "");
        item.setSourceBranch(mr.optString("source_branch", ""));
        item.setTargetBranch(mr.optString("target_branch", ""));
        if (mr.has("merged_at") && !mr.isNull("merged_at")) {
            item.setMergedAt(LocalDateTime.parse(mr.getString("merged_at").substring(0, 19)));
        }
        item.setStatus("running");
        item.setStartedAt(LocalDateTime.now());
        return item;
    }

    private void finishItem(AiMrAnalysisJobItem item, String status, Long evaluationId, String errorMessage) {
        item.setStatus(status);
        if (evaluationId != null) {
            item.setEvaluationId(evaluationId);
        }
        if (errorMessage != null) {
            item.setErrorMessage(errorMessage);
        }
        item.setFinishedAt(LocalDateTime.now());
        aiMrAnalysisJobItemRepository.save(item);
    }

    private List<Integer> itemIidsByStatus(List<AiMrAnalysisJobItem> items, String status) {
        return items.stream()
                .filter(item -> status.equals(item.getStatus()))
                .map(AiMrAnalysisJobItem::getMrIid)
                .filter(Objects::nonNull)
                .toList();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate prompt hash", e);
        }
    }

    public List<AiMrEvaluation> findAnalyzedMRs(String projectId, String since, String until, String userName,
            String refName) {
        LocalDateTime start = parseStartDateTime(since);
        LocalDateTime end = parseEndDateTime(until);

        List<AiMrEvaluation> evaluations;
        boolean hasUserName = userName != null && !userName.isBlank();
        boolean hasRefName = refName != null && !refName.isBlank();

        if (hasUserName && hasRefName) {
            evaluations = aiMrEvaluationRepository.findByProjectIdAndAuthorUsernameAndBranchAndMergedAtBetween(
                    projectId, userName, refName, start, end);
        } else if (hasUserName) {
            evaluations = aiMrEvaluationRepository.findByProjectIdAndAuthorUsernameAndMergedAtBetween(
                    projectId, userName, start, end);
        } else if (hasRefName) {
            evaluations = aiMrEvaluationRepository.findByProjectIdAndBranchAndMergedAtBetween(
                    projectId, refName, start, end);
        } else {
            evaluations = aiMrEvaluationRepository.findByProjectIdAndMergedAtBetween(projectId, start, end);
        }

        log.info("Loaded {} pre-analyzed MR evaluations from DB. projectId={}, since={}, until={}, userName={}, refName={}",
                evaluations.size(), projectId, since, until, userName, refName);
        return evaluations;
    }

    public AiAnalysisProgressDTO getAnalysisProgress(String projectId, String since, String until, String userName,
            String refName) {
        AiAnalysisProgressDTO progress = analysisProgress.get(progressKey(projectId, since, until, userName, refName));
        if (progress != null) {
            return progress;
        }

        return new AiAnalysisProgressDTO(0, 0, 0, null, "idle", "AI分析は開始されていません");
    }

    private AiMrEvaluation analyzeAndSaveMR(String projectId, AnalysisTarget target, String requestedUserName) {
        return analyzeAndSaveMR(projectId, target, requestedUserName, null, null);
    }

    private AiMrEvaluation analyzeAndSaveMR(
            String projectId,
            AnalysisTarget target,
            String requestedUserName,
            AiMrAnalysisJob job,
            AiPromptVersion promptVersion) {
        JSONObject mrJson = target.mrJson();
        int iid = mrJson.getInt("iid");
        JSONObject authorJson = mrJson.optJSONObject("author");
        String author = authorJson != null ? authorJson.optString("username", "") : "";
        String targetBranch = mrJson.optString("target_branch", "");
        String sourceBranch = mrJson.optString("source_branch", "");
        LocalDateTime mergedAt = LocalDateTime.parse(mrJson.getString("merged_at").substring(0, 19));

        String diff = target.diff();
        int diffLines = target.lineCount();

        log.info("Analyzing MR {} for project {} (diff lines: {})", iid, projectId, diffLines);

        if (diff == null || diff.isEmpty()) {
            log.warn("Empty diff for MR {}", iid);
            return null;
        }
        String aiResponse = promptVersion == null
                ? geminiService.analyzeDiff(diff)
                : geminiService.analyzeDiff(diff, promptVersion.getPromptText());
        if (aiResponse == null) {
            return null;
        }

        try {
            JSONObject evalJson = new JSONObject(aiResponse);
            AiMrEvaluation eval = new AiMrEvaluation();
            eval.setProjectId(projectId);
            eval.setMrIid(iid);
            eval.setAuthorUsername(requestedUserName != null && !requestedUserName.isBlank() ? requestedUserName : author);
            eval.setTargetBranch(targetBranch);
            eval.setSourceBranch(sourceBranch);
            eval.setMergedAt(mergedAt);
            eval.setAnalyzedAt(LocalDateTime.now());
            if (job != null) {
                eval.setJobId(job.getId());
            }
            if (promptVersion != null) {
                eval.setPromptVersionId(promptVersion.getId());
                eval.setModel(promptVersion.getModel());
                eval.setPromptHash(promptVersion.getPromptHash());
            } else {
                eval.setModel(geminiService.currentModel());
            }

            String type = metricStringValue(evalJson, "type");
            eval.setChangeType(type);
            eval.setTypeCoefficient(typeCoefficients.getOrDefault(type, 1.0));

            String complexity = metricStringValue(evalJson, "complexity");
            eval.setComplexity(complexity);
            eval.setComplexityCoefficient(complexityCoefficients.getOrDefault(complexity, 1.0));

            String maintainability = metricStringValue(evalJson, "maintainability");
            eval.setMaintainability(maintainability);
            eval.setMaintainabilityCoefficient(maintainabilityCoefficients.getOrDefault(maintainability, 1.0));

            eval.setHasBug(metricBooleanValue(evalJson, "bug"));
            eval.setBugCoefficient(eval.getHasBug() ? 0.0 : 1.0);

            String contribution = metricStringValue(evalJson, "contribution");
            eval.setContribution(contribution);
            eval.setContributionCoefficient(contributionCoefficients.getOrDefault(contribution, 1.0));

            eval.setReasoning(reasoningJson(evalJson).toString());
            eval.setRawResponse(aiResponse);

            return aiMrEvaluationRepository.save(eval);
        } catch (Exception e) {
            log.error("Failed to parse AI response for MR {}", iid, e);
            return null;
        }
    }

    private String metricStringValue(JSONObject evalJson, String key) {
        Object value = evalJson.get(key);
        if (value instanceof JSONObject metricJson) {
            return metricJson.getString("value");
        }
        return evalJson.getString(key);
    }

    private Boolean metricBooleanValue(JSONObject evalJson, String key) {
        Object value = evalJson.get(key);
        if (value instanceof JSONObject metricJson) {
            return metricJson.getBoolean("value");
        }
        return evalJson.getBoolean(key);
    }

    private JSONObject reasoningJson(JSONObject evalJson) {
        JSONObject reasonings = new JSONObject();
        putMetricReasoning(reasonings, evalJson, "type");
        putMetricReasoning(reasonings, evalJson, "complexity");
        putMetricReasoning(reasonings, evalJson, "maintainability");
        putMetricReasoning(reasonings, evalJson, "bug");
        putMetricReasoning(reasonings, evalJson, "contribution");

        if (reasonings.length() == 0 && evalJson.has("reasoning")) {
            reasonings.put("overall", evalJson.getString("reasoning"));
        }
        return reasonings;
    }

    private void putMetricReasoning(JSONObject target, JSONObject source, String key) {
        Object value = source.opt(key);
        if (value instanceof JSONObject metricJson && metricJson.has("reasoning")) {
            target.put(key, metricJson.getString("reasoning"));
        }
    }

    private List<AnalysisTarget> buildAnalysisTargets(String projectId, List<JSONObject> mergedMRs, String userName) {
        List<AnalysisTarget> targets = new ArrayList<>();
        for (JSONObject mr : mergedMRs) {
            int iid = mr.optInt("iid");
            if (gitHubRepositoryService.supports(projectId)) {
                JSONObject author = mr.optJSONObject("author");
                String authorUsername = author == null ? "" : author.optString("username", "");
                if (userName != null && !userName.isBlank() && !userName.equalsIgnoreCase(authorUsername)) {
                    continue;
                }
                MergeService.MergeRequestDiff diffResult = mergeService.getMergeRequestDiff(projectId, iid);
                if (diffResult.diff != null && !diffResult.diff.isBlank()) {
                    targets.add(new AnalysisTarget(mr, diffResult.diff, diffResult.lineCount));
                }
                continue;
            }
            if (userName == null || userName.isBlank()) {
                MergeService.MergeRequestDiff diffResult = mergeService.getMergeRequestDiff(projectId, iid);
                targets.add(new AnalysisTarget(mr, diffResult.diff, diffResult.lineCount));
                continue;
            }

            List<JSONObject> authoredCommits = fetchMergeRequestCommits(projectId, iid).stream()
                    .filter(commit -> isCommitAuthoredBy(commit, userName))
                    .toList();
            if (authoredCommits.isEmpty()) {
                continue;
            }

            MergeService.MergeRequestDiff diffResult = getCommitDiffs(projectId, authoredCommits);
            if (diffResult.diff != null && !diffResult.diff.isBlank()) {
                targets.add(new AnalysisTarget(mr, diffResult.diff, diffResult.lineCount));
            }
        }
        return targets;
    }

    private List<JSONObject> fetchMergedMRs(String projectId, String since, String until, String refName) {
        if (gitHubRepositoryService.supports(projectId)) {
            return mergeService.fetchAllMergedMergeRequests(projectId, since, until, refName);
        }
        List<JSONObject> allMergedMRs = new ArrayList<>();
        Set<String> seenMergeRequestIds = new HashSet<>();
        if (refName != null && !refName.isEmpty()) {
            fetchMergedMRs(projectId, since, until, "target_branch", refName, allMergedMRs, seenMergeRequestIds);
            fetchMergedMRs(projectId, since, until, "source_branch", refName, allMergedMRs, seenMergeRequestIds);
            return allMergedMRs;
        }

        fetchMergedMRs(projectId, since, until, null, null, allMergedMRs, seenMergeRequestIds);
        return allMergedMRs;
    }

    private void fetchMergedMRs(String projectId, String since, String until, String branchParamName, String branchName,
            List<JSONObject> allMergedMRs, Set<String> seenMergeRequestIds) {
        int page = 1;
        int perPage = 100;

        String apiSince = (since != null && !since.isEmpty()) ? since + "T00:00:00Z" : null;
        String apiUntil = (until != null && !until.isEmpty()) ? until + "T23:59:59Z" : null;

        try {
            while (true) {
                StringBuilder urlBuilder = new StringBuilder(String.format(
                        "%s/projects/%s/merge_requests?page=%d&per_page=%d&state=merged",
                        gitlabBase, urlEncode(projectId), page, perPage));

                if (apiSince != null)
                    urlBuilder.append("&merged_after=").append(urlEncode(apiSince));
                if (apiUntil != null)
                    urlBuilder.append("&merged_before=").append(urlEncode(apiUntil));
                if (branchParamName != null && branchName != null && !branchName.isEmpty())
                    urlBuilder.append("&").append(branchParamName).append("=").append(urlEncode(branchName));

                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET,
                        URI.create(urlBuilder.toString()));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray mrs = new JSONArray(res.getBody());

                if (mrs.isEmpty())
                    break;
                for (int i = 0; i < mrs.length(); i++) {
                    JSONObject mr = mrs.getJSONObject(i);
                    // サーバーサイドで日付を再検証
                    if (mr.has("merged_at") && !mr.isNull("merged_at")) {
                        ZonedDateTime mergedAt = ZonedDateTime.parse(mr.getString("merged_at"));
                        ZonedDateTime startRange = (apiSince != null) ? ZonedDateTime.parse(apiSince) : null;
                        ZonedDateTime endRange = (apiUntil != null) ? ZonedDateTime.parse(apiUntil) : null;

                        if (startRange != null && mergedAt.isBefore(startRange)) {
                            continue; // 期間より前のMRはスキップ
                        }
                        if (endRange != null && mergedAt.isAfter(endRange)) {
                            continue; // 期間より後のMRはスキップ
                        }
                        String mergeRequestId = mr.optString("id", mr.optString("iid", ""));
                        if (mergeRequestId.isBlank() || seenMergeRequestIds.add(mergeRequestId)) {
                            allMergedMRs.add(mr);
                        }
                    }
                }
                if (mrs.length() < perPage)
                    break;
                page++;
            }
        } catch (Exception e) {
            log.error("Error fetching merged MRs: {}", e.getMessage(), e);
        }
    }

    private List<JSONObject> fetchMergeRequestCommits(String projectId, int mrIid) {
        List<JSONObject> commits = new ArrayList<>();
        int page = 1;
        int perPage = 100;
        try {
            while (true) {
                String url = String.format("%s/projects/%s/merge_requests/%d/commits?page=%d&per_page=%d",
                        gitlabBase, urlEncode(projectId), mrIid, page, perPage);
                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray pageCommits = new JSONArray(res.getBody());
                if (pageCommits.isEmpty()) {
                    break;
                }

                for (int i = 0; i < pageCommits.length(); i++) {
                    commits.add(pageCommits.getJSONObject(i));
                }
                if (pageCommits.length() < perPage) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            log.error("Error fetching commits for MR {}: {}", mrIid, e.getMessage(), e);
        }
        return commits;
    }

    private MergeService.MergeRequestDiff getCommitDiffs(String projectId, List<JSONObject> commits) {
        StringBuilder diffBuilder = new StringBuilder();
        int maxLines = 5000;
        int currentLines = 0;

        for (JSONObject commit : commits) {
            String sha = commit.optString("id", commit.optString("short_id", ""));
            if (sha.isBlank()) {
                continue;
            }

            try {
                String url = String.format("%s/projects/%s/repository/commits/%s/diff",
                        gitlabBase, urlEncode(projectId), urlEncode(sha));
                RequestEntity<Void> req = new RequestEntity<>(headers(), HttpMethod.GET, URI.create(url));
                ResponseEntity<String> res = rt.exchange(req, String.class);
                JSONArray diffs = new JSONArray(res.getBody());

                diffBuilder.append("Commit: ").append(sha).append("\n");
                for (int i = 0; i < diffs.length(); i++) {
                    JSONObject diff = diffs.getJSONObject(i);
                    String fileName = diff.optString("new_path", diff.optString("old_path", "unknown"));
                    if (fileName.endsWith(".lock") || fileName.endsWith("-lock.json") || fileName.endsWith(".bin")) {
                        continue;
                    }

                    String diffText = diff.optString("diff", "");
                    String[] lines = diffText.split("\n");
                    diffBuilder.append("File: ").append(fileName).append("\n");
                    for (String line : lines) {
                        if (currentLines >= maxLines) {
                            diffBuilder.append("... (diff truncated)\n");
                            return new MergeService.MergeRequestDiff(diffBuilder.toString(), currentLines);
                        }
                        diffBuilder.append(line).append("\n");
                        currentLines++;
                    }
                    diffBuilder.append("\n");
                }
            } catch (Exception e) {
                log.error("Error fetching commit diff {}: {}", sha, e.getMessage(), e);
            }
        }

        return new MergeService.MergeRequestDiff(diffBuilder.toString(), currentLines);
    }

    private boolean isCommitAuthoredBy(JSONObject commit, String userName) {
        String normalizedUserName = normalizeUserIdentity(userName);
        if (normalizedUserName.isBlank()) {
            return false;
        }

        String userEmployeeNumber = extractEmployeeNumber(normalizedUserName);
        return List.of(
                commit.optString("author_name", ""),
                commit.optString("author_email", ""),
                commit.optString("committer_name", ""),
                commit.optString("committer_email", ""))
                .stream()
                .map(this::normalizeUserIdentity)
                .anyMatch(value -> {
                    String valueEmployeeNumber = extractEmployeeNumber(value);
                    return value.equals(normalizedUserName)
                            || emailLocalPart(value).equals(normalizedUserName)
                            || (!userEmployeeNumber.isBlank() && userEmployeeNumber.equals(valueEmployeeNumber));
                });
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("PRIVATE-TOKEN", gitlabToken);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    private void updateProgress(String progressKey, AiAnalysisProgressDTO progress) {
        analysisProgress.put(progressKey, progress);
    }

    private String progressKey(String projectId, String since, String until, String userName, String refName) {
        return String.join("|",
                normalizeKeyPart(projectId),
                normalizeKeyPart(since),
                normalizeKeyPart(until),
                normalizeKeyPart(userName),
                normalizeKeyPart(refName));
    }

    private String normalizeKeyPart(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private LocalDateTime parseStartDateTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        if (value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay();
        }
        return ZonedDateTime.parse(value).toLocalDateTime();
    }

    private LocalDateTime parseEndDateTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        }
        if (value.length() == 10) {
            return LocalDate.parse(value).atTime(23, 59, 59);
        }
        return ZonedDateTime.parse(value).toLocalDateTime();
    }

    private String normalizeUserIdentity(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String emailLocalPart(String value) {
        int atIndex = value.indexOf('@');
        return atIndex > 0 ? value.substring(0, atIndex) : value;
    }

    private String extractEmployeeNumber(String value) {
        if (value == null) {
            return "";
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record AnalysisTarget(JSONObject mrJson, String diff, int lineCount) {
    }
}
