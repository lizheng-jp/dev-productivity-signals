package dev.productivity.signals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiAnalysisProgressDTO {
    private int total;
    private int current;
    private int processed;
    private Integer currentMrIid;
    private String status;
    private String message;
}
