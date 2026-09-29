package dev.productivity.signals.dto;

import lombok.Data;

@Data
public class MetricWeightDTO {
    private Integer id;
    private String metricKey;
    private String parentKey;
    private double weight;
    private boolean isActive;
    private double minThreshold;
    private double maxThreshold;
    private boolean isPositiveMetric;
    private String description;
}
