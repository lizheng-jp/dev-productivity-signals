package dev.productivity.signals.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import dev.productivity.signals.entity.MetricWeightManagement;

import java.util.List;

public interface MetricWeightManagementRepository extends JpaRepository<MetricWeightManagement, Integer> {

    List<MetricWeightManagement> findByIsActive(boolean isActive);
}
