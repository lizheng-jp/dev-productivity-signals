package dev.productivity.signals.service;

import dev.productivity.signals.repository.MetricWeightManagementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DemoMetricWeightSeedService {

    private final JdbcTemplate jdbcTemplate;
    private final MetricWeightManagementRepository metricWeightRepository;
    private final DemoMockDataService demoMockDataService;

    @Value("${demo.mock.enabled:false}")
    private boolean demoMockEnabled;

    @Bean
    ApplicationRunner demoMetricWeightSeeder() {
        return args -> {
            if (!demoMockEnabled) {
                return;
            }

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS evaluation_metrics_config (
                        id INTEGER PRIMARY KEY,
                        metric_key VARCHAR(128) NOT NULL,
                        parent_key VARCHAR(128),
                        weight DOUBLE PRECISION NOT NULL,
                        is_active BOOLEAN NOT NULL,
                        min_threshold DOUBLE PRECISION NOT NULL,
                        max_threshold DOUBLE PRECISION NOT NULL,
                        is_positive_metric BOOLEAN NOT NULL,
                        description TEXT
                    )
                    """);

            metricWeightRepository.saveAll(demoMockDataService.getMetricWeightEntities());
            log.info("Seeded {} demo metric weight rows into evaluation_metrics_config.",
                    demoMockDataService.getMetricWeightEntities().size());
        };
    }
}
