package dev.productivity.signals.service;

import dev.productivity.signals.dto.ProjectMemberGroupDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Contributor retention, the one Satisfaction metric in the SPACE framework that does
 * not need a survey. Used as the Satisfaction score for GitHub projects without survey
 * responses: the share of contributors active in the previous window who are still
 * active in the current one. "Active" means authoring a commit or a merged pull request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContributorRetentionService {

    /** Below this many previously active contributors one person swings the rate too far to score. */
    public static final int MIN_PREVIOUS_CONTRIBUTORS = 5;

    private final ActiveMemberService activeMemberService;
    private final GitHubRepositoryService gitHubRepositoryService;

    public record Retention(int previousActive, int retained, double rate) {
    }

    public record Window(String since, String until) {
    }

    public boolean supports(String projectId) {
        return gitHubRepositoryService.supports(projectId);
    }

    public Optional<Retention> calculate(String projectId, String since, String until, String refName) {
        if (!supports(projectId) || since == null || until == null) {
            return Optional.empty();
        }
        try {
            Window previous = previousWindow(since, until);
            Set<String> current = activeUserCodes(projectId, since, until, refName);
            Set<String> before = activeUserCodes(projectId, previous.since(), previous.until(), refName);
            return calculate(before, current);
        } catch (Exception e) {
            log.warn("Contributor retention unavailable for {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    /** The window of equal length that ends the day before {@code since}. */
    static Window previousWindow(String since, String until) {
        LocalDate start = LocalDate.parse(since.substring(0, 10));
        LocalDate end = LocalDate.parse(until.substring(0, 10));
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate previousEnd = start.minusDays(1);
        return new Window(previousEnd.minusDays(days - 1).toString(), previousEnd.toString());
    }

    static Optional<Retention> calculate(Set<String> previousActive, Set<String> currentActive) {
        if (previousActive.size() < MIN_PREVIOUS_CONTRIBUTORS) {
            return Optional.empty();
        }
        Set<String> retained = new HashSet<>(previousActive);
        retained.retainAll(currentActive);
        double rate = Math.round(retained.size() * 1000.0 / previousActive.size()) / 10.0;
        return Optional.of(new Retention(previousActive.size(), retained.size(), rate));
    }

    private Set<String> activeUserCodes(String projectId, String since, String until, String refName) {
        List<ProjectMemberGroupDTO> members = activeMemberService.getActiveProjectMembers(projectId, since, until, refName);
        return members.stream().map(ProjectMemberGroupDTO::getUserCode).collect(Collectors.toSet());
    }
}
