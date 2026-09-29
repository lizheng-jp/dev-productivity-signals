package dev.productivity.signals.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;


@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectDTO {

    private String id;
    private String name;
    private String description;
    @JsonAlias("default_branch")
    private String defaultBranch;
    private String provider;
    @JsonAlias("path_with_namespace")
    private String fullName;
    @JsonAlias("web_url")
    private String webUrl;

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id == null ? null : String.valueOf(id);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getWebUrl() {
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }
}
