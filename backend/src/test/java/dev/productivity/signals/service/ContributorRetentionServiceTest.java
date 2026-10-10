package dev.productivity.signals.service;

import dev.productivity.signals.dto.ProjectMemberGroupDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContributorRetentionServiceTest {

    @Test
    void previousWindowHasTheSameLengthAndEndsTheDayBefore() {
        var window = ContributorRetentionService.previousWindow("2026-09-10", "2026-10-10");

        assertThat(window.since()).isEqualTo("2026-08-10");
        assertThat(window.until()).isEqualTo("2026-09-09");
    }

    @Test
    void retentionIsTheShareOfPreviousContributorsStillActive() {
        var retention = ContributorRetentionService.calculate(
                Set.of("A", "B", "C", "D"), Set.of("B", "D", "E"));

        assertThat(retention).hasValueSatisfying(value -> {
            assertThat(value.previousActive()).isEqualTo(4);
            assertThat(value.retained()).isEqualTo(2);
            assertThat(value.rate()).isEqualTo(50.0);
        });
    }

    @Test
    void skipsScoringWhenTooFewContributorsWereActiveBefore() {
        assertThat(ContributorRetentionService.calculate(Set.of("A", "B"), Set.of("A", "B"))).isEmpty();
    }

    @Test
    void comparesActiveMembersOfTwoEqualWindows() {
        var activeMembers = mock(ActiveMemberService.class);
        var github = mock(GitHubRepositoryService.class);
        String project = "github~google~gson";
        when(github.supports(project)).thenReturn(true);
        when(activeMembers.getActiveProjectMembers(project, "2026-09-08", "2026-09-14", "main"))
                .thenReturn(members("A", "B", "X"));
        when(activeMembers.getActiveProjectMembers(project, "2026-09-01", "2026-09-07", "main"))
                .thenReturn(members("A", "B", "C"));

        var retention = new ContributorRetentionService(activeMembers, github)
                .calculate(project, "2026-09-08", "2026-09-14", "main");

        assertThat(retention).hasValueSatisfying(value -> assertThat(value.rate()).isEqualTo(66.7));
    }

    @Test
    void ignoresNonGithubProjects() {
        var activeMembers = mock(ActiveMemberService.class);
        var github = mock(GitHubRepositoryService.class);

        var retention = new ContributorRetentionService(activeMembers, github)
                .calculate("demo~productivity", "2026-09-08", "2026-09-14", "main");

        assertThat(retention).isEmpty();
        verifyNoInteractions(activeMembers);
    }

    private static List<ProjectMemberGroupDTO> members(String... userCodes) {
        return java.util.Arrays.stream(userCodes)
                .map(code -> new ProjectMemberGroupDTO(code, null, null, null))
                .toList();
    }
}
