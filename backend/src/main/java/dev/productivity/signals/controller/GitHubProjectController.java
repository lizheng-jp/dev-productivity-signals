package dev.productivity.signals.controller;

import dev.productivity.signals.dto.GitHubProjectRequestDTO;
import dev.productivity.signals.dto.ProjectDTO;
import dev.productivity.signals.service.GitHubRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
public class GitHubProjectController {

    private final GitHubRepositoryService gitHubRepositoryService;

    @PostMapping("/projects/resolve")
    public ProjectDTO resolveProject(@RequestBody GitHubProjectRequestDTO request) {
        return gitHubRepositoryService.resolveProject(request.repositoryUrl());
    }

    @GetMapping("/rate-limit")
    public Map<String, Object> getRateLimit() {
        return gitHubRepositoryService.getRateLimit();
    }
}
