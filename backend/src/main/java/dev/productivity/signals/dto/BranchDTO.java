package dev.productivity.signals.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchDTO {

    private String name;
    private Boolean merged;

    @JsonProperty("protected")
    private Boolean isProtected;

    @JsonProperty("default")
    private Boolean isDefault;

    @JsonProperty("web_url")
    private String webUrl;

    @JsonProperty("first_commit_at")
    private String firstCommitAt;

    @JsonProperty("last_commit_at")
    private String lastCommitAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getMerged() {
        return merged;
    }

    public void setMerged(Boolean merged) {
        this.merged = merged;
    }

    public Boolean getIsProtected() {
        return isProtected;
    }

    public void setIsProtected(Boolean isProtected) {
        this.isProtected = isProtected;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getWebUrl() {
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }

    public String getFirstCommitAt() {
        return firstCommitAt;
    }

    public void setFirstCommitAt(String firstCommitAt) {
        this.firstCommitAt = firstCommitAt;
    }

    public String getLastCommitAt() {
        return lastCommitAt;
    }

    public void setLastCommitAt(String lastCommitAt) {
        this.lastCommitAt = lastCommitAt;
    }
}
