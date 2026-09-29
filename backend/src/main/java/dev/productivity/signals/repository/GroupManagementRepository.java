package dev.productivity.signals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import dev.productivity.signals.entity.GroupManagement;

@Repository
public interface GroupManagementRepository extends JpaRepository<GroupManagement, String>{

}
