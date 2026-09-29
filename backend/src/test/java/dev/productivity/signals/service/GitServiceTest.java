package dev.productivity.signals.service;

import dev.productivity.signals.dto.ProjectDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitServiceTest {

    @Test
    void keepsNumericGitLabIdsCompatibleWithStringProjectKeys() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GitService service = new GitService(restTemplate, mock(GitHubRepositoryService.class));
        ReflectionTestUtils.setField(service, "base", "https://gitlab.example.test/api/v4");
        ReflectionTestUtils.setField(service, "token", "");
        ReflectionTestUtils.setField(service, "projectsCacheTtlMinutes", 60L);

        server.expect(requestTo("https://gitlab.example.test/api/v4/projects?per_page=100&page=1&order_by=id&sort=asc"))
                .andRespond(withSuccess("""
                        [{
                          "id": 123,
                          "name": "sample-project",
                          "description": "Sample",
                          "default_branch": "main",
                          "path_with_namespace": "team/sample-project",
                          "web_url": "https://gitlab.example.test/team/sample-project"
                        }]
                        """, MediaType.APPLICATION_JSON));

        List<ProjectDTO> projects = service.getAllProjects();

        assertThat(projects).hasSize(1);
        assertThat(projects.get(0).getId()).isEqualTo("123");
        assertThat(projects.get(0).getProvider()).isEqualTo("gitlab");
        assertThat(projects.get(0).getFullName()).isEqualTo("team/sample-project");
        server.verify();
    }
}
