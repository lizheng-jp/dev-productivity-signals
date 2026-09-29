package dev.productivity.signals.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AiMrAnalysisJobRequestDTO {
    private String projectId;
    private String refName;
    private LocalDate sinceDate;
    private LocalDate untilDate;
    private Long promptVersionId;
    private String requestedBy;
}
