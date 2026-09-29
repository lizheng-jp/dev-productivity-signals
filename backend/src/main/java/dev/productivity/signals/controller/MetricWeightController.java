package dev.productivity.signals.controller;

import dev.productivity.signals.dto.MetricWeightDTO;
import dev.productivity.signals.service.DemoMockDataService;
import dev.productivity.signals.service.MetricWeightService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/metric-weights")
@RequiredArgsConstructor
public class MetricWeightController {

    private final MetricWeightService metricWeightService;
    private final DemoMockDataService demoMockDataService;

    @GetMapping
    public ResponseEntity<List<MetricWeightDTO>> getAllMetricWeights() {
        if (demoMockDataService.isEnabled()) {
            return ResponseEntity.ok(demoMockDataService.getMetricWeights());
        }
        return ResponseEntity.ok(metricWeightService.getAllMetricWeights());
    }

    @PutMapping
    public ResponseEntity<List<MetricWeightDTO>> updateMetricWeights(@RequestBody List<MetricWeightDTO> metricWeights) {
        return ResponseEntity.ok(metricWeightService.updateMetricWeights(metricWeights));
    }
}
