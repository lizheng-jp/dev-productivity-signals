package dev.productivity.signals.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "space-metric")
public class SpaceMetricProperties {
    private Map<String, Dimension> dimensions;

    @Data
    public static class Dimension {
        private double weight;
        private Map<String, Double> metrics;
    }
}
