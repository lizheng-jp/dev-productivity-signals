package dev.productivity.signals.service;

import dev.productivity.signals.dto.MetricWeightDTO;
import dev.productivity.signals.entity.MetricWeightManagement;
import dev.productivity.signals.repository.MetricWeightManagementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static dev.productivity.signals.util.SpaceMetricConstants.mergedLeadTimeHours;

@Service
@RequiredArgsConstructor
public class MetricWeightService {

    private static final double WEIGHT_SUM_TOLERANCE = 0.001;

    private final MetricWeightManagementRepository metricWeightRepository;

    public List<MetricWeightDTO> getAllMetricWeights() {
        return metricWeightRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<MetricWeightDTO> updateMetricWeights(List<MetricWeightDTO> metricWeightDTOs) {
        if (metricWeightDTOs == null) {
            throw invalidWeightSum("Metric weights are required");
        }
        metricWeightDTOs.forEach(this::applyParentOverride);
        validateWeightSums(metricWeightDTOs);

        List<MetricWeightManagement> metricWeights = metricWeightDTOs.stream()
                .map(this::convertToEntity)
                .collect(Collectors.toList());
        metricWeightRepository.saveAll(metricWeights);
        return metricWeightDTOs;
    }

    private void validateWeightSums(List<MetricWeightDTO> metricWeights) {
        List<MetricWeightDTO> activeParents = metricWeights.stream()
                .filter(MetricWeightDTO::isActive)
                .filter(metric -> metric.getParentKey() == null || metric.getParentKey().isBlank())
                .toList();
        validateGroup(activeParents, "Active SPACE dimension weights must total 1");

        for (MetricWeightDTO parent : activeParents) {
            List<MetricWeightDTO> activeChildren = metricWeights.stream()
                    .filter(MetricWeightDTO::isActive)
                    .filter(metric -> Objects.equals(parent.getMetricKey(), metric.getParentKey()))
                    .toList();
            if (!activeChildren.isEmpty()) {
                validateGroup(
                        activeChildren,
                        "Active metric weights for dimension " + parent.getMetricKey() + " must total 1");
            }
        }
    }

    private void validateGroup(List<MetricWeightDTO> metrics, String message) {
        if (metrics.isEmpty()
                || metrics.stream().anyMatch(metric -> !Double.isFinite(metric.getWeight())
                        || metric.getWeight() < 0
                        || metric.getWeight() > 1)) {
            throw invalidWeightSum(message);
        }

        double sum = metrics.stream().mapToDouble(MetricWeightDTO::getWeight).sum();
        if (Math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw invalidWeightSum(message);
        }
    }

    private ResponseStatusException invalidWeightSum(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private MetricWeightDTO convertToDTO(MetricWeightManagement metricWeight) {
        MetricWeightDTO metricWeightDTO = new MetricWeightDTO();
        BeanUtils.copyProperties(metricWeight, metricWeightDTO);
        applyParentOverride(metricWeightDTO);
        return metricWeightDTO;
    }

    private MetricWeightManagement convertToEntity(MetricWeightDTO metricWeightDTO) {
        applyParentOverride(metricWeightDTO);
        MetricWeightManagement metricWeight = new MetricWeightManagement();
        BeanUtils.copyProperties(metricWeightDTO, metricWeight);
        return metricWeight;
    }

    private void applyParentOverride(MetricWeightDTO metricWeightDTO) {
        if (mergedLeadTimeHours.equals(metricWeightDTO.getMetricKey())) {
            metricWeightDTO.setParentKey("efficiency");
        }
    }
}
