package dev.productivity.signals.repository;

import dev.productivity.signals.entity.AiPromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiPromptVersionRepository extends JpaRepository<AiPromptVersion, Long> {
    Optional<AiPromptVersion> findFirstByActiveTrueOrderByCreatedAtDesc();

    Optional<AiPromptVersion> findByVersionKey(String versionKey);

    List<AiPromptVersion> findByActiveTrueOrderByCreatedAtDesc();

    List<AiPromptVersion> findAllByOrderByCreatedAtDesc();
}
