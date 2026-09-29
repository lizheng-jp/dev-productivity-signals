package dev.productivity.signals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import lombok.Data;


@Data
@Entity
@Table(name = "evaluation_metrics_config")
public class MetricWeightManagement {

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "parent_key")
    private String parentKey;

    @Column(name = "weight")
    private double weight;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "min_threshold")
    private double minThreshold;

    @Column(name = "max_threshold")
    private double maxThreshold;

    @Column(name = "is_positive_metric")
    private boolean isPositiveMetric;

    @Column(name = "description")
    private String description;

}
