package dev.productivity.signals.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import dev.productivity.signals.dto.GroupCreateDTO;
import dev.productivity.signals.dto.GroupDTO;
import dev.productivity.signals.dto.UserCreateDTO;
import dev.productivity.signals.dto.UserDTO;
import dev.productivity.signals.entity.GroupManagement;
import dev.productivity.signals.entity.GroupUserManagement;
import dev.productivity.signals.entity.GroupUserManagementKey;
import dev.productivity.signals.repository.GroupManagementRepository;
import dev.productivity.signals.repository.GroupUserManagementRepository;

import jakarta.transaction.Transactional;

@Service
public class GroupService {

    private final GroupManagementRepository groupRepo;
    private final GroupUserManagementRepository groupUserRepo;

    public GroupService(GroupManagementRepository groupRepo, GroupUserManagementRepository groupUserRepo) {
        this.groupRepo = groupRepo;
        this.groupUserRepo = groupUserRepo;
    }

    // グループの一覧・グループとユーザ取得ロジック
    public List<GroupDTO> getGroupsWithUsers() {
        List<GroupManagement> groups = groupRepo.findAll();
        List<GroupDTO> dtoList = new ArrayList<>();
        for (GroupManagement group : groups) {
            List<GroupUserManagement> users = groupUserRepo.findById_GroupId(group.getGroupId());
            List<UserDTO> userDtos = users.stream()
                    .map(u -> new UserDTO(u.getId().getUserCode(), u.getUserName()))
                    .collect(Collectors.toList());
            dtoList.add(new GroupDTO(group.getGroupId(), group.getGroupName(), userDtos));
        }
        return dtoList;
    }

    // グループ登録
    @Transactional
    public GroupDTO createGroup(GroupCreateDTO dto) {
        GroupManagement entity = new GroupManagement();
        entity.setGroupId(dto.getGroupId());
        entity.setGroupName(dto.getGroupName());
        groupRepo.save(entity);
        return new GroupDTO(entity.getGroupId(), entity.getGroupName(), List.of());
    }

    // ユーザ登録
    @Transactional
    public UserDTO addUserToGroup(String groupId, UserCreateDTO dto) {
        // グループ存在チェック
        Optional<GroupManagement> group = groupRepo.findById(groupId);
        if (group.isEmpty())
            throw new RuntimeException("Group not found: " + groupId);
        // 作成
        GroupUserManagementKey key = new GroupUserManagementKey();
        key.setGroupId(groupId);
        key.setUserCode(dto.getUserCode());
        GroupUserManagement gu = new GroupUserManagement();
        gu.setId(key);
        gu.setUserName(dto.getUserName());
        groupUserRepo.save(gu);
        return new UserDTO(dto.getUserCode(), dto.getUserName());
    }

    // グループ削除
    @Transactional
    public void deleteGroup(String groupId) {
        // 先にユーザーも削除
        groupUserRepo.findById_GroupId(groupId).forEach(gu -> groupUserRepo.delete(gu));
        groupRepo.deleteById(groupId);
    }

    // ユーザ削除
    @Transactional
    public void deleteUserFromGroup(String groupId, String userCode) {
        GroupUserManagementKey key = new GroupUserManagementKey();
        key.setGroupId(groupId);
        key.setUserCode(userCode);
        groupUserRepo.deleteById(key);
    }

    /**
     * ユーザーコードから所属するグループとユーザーの情報を検索する
     *
     * @param userCode ユーザーコード (GitLabのusername)
     * @return Optional<GroupUserManagement>
     *         ユーザーとグループの関連情報。見つからない場合はOptional.empty()。
     */
    public Optional<GroupUserManagement> findUserGroupInfoByUserCode(String userCode) {
        // userCodeを大文字に変換して検索
        return groupUserRepo.findById_UserCode(userCode.toUpperCase());
    }

    /**
     * グループIDからグループ情報を検索する
     *
     * @param groupId グループID
     * @return Optional<GroupManagement> グループの情報。見つからない場合はOptional.empty()。
     */
    public Optional<GroupManagement> findGroupById(String groupId) {
        return groupRepo.findById(groupId);
    }
}