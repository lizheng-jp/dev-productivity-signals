package dev.productivity.signals.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dev.productivity.signals.entity.GroupUserManagement;
import dev.productivity.signals.entity.GroupUserManagementKey;

@Repository
public interface GroupUserManagementRepository extends JpaRepository<GroupUserManagement, GroupUserManagementKey>{

    // groupIdでユーザー一覧取得例
    List<GroupUserManagement> findById_GroupId(String groupId);

    // userCodeでユーザーを検索
    Optional<GroupUserManagement> findById_UserCode(String userCode);

}
