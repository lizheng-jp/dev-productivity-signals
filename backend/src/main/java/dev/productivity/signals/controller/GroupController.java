package dev.productivity.signals.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.productivity.signals.dto.GroupCreateDTO;
import dev.productivity.signals.dto.GroupDTO;
import dev.productivity.signals.dto.UserCreateDTO;
import dev.productivity.signals.dto.UserDTO;
import dev.productivity.signals.service.DemoMockDataService;
import dev.productivity.signals.service.GroupService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.RequestBody;

@RestController
@Tag(name = "グループ管理関連")
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;
    private final DemoMockDataService demoMockDataService;

    public GroupController(GroupService groupService, DemoMockDataService demoMockDataService) {
        this.groupService = groupService;
        this.demoMockDataService = demoMockDataService;
    }

    @Operation(summary = "グループを取得します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @GetMapping
    public List<GroupDTO> getGroupsWithUsers() {
        if (demoMockDataService.isEnabled()) {
            return demoMockDataService.getGroups();
        }
        return groupService.getGroupsWithUsers();
    }

    // グループ登録
    @Operation(summary = "新規グループを追加します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupDTO createGroup(@RequestBody GroupCreateDTO dto) {
        if (demoMockDataService.isEnabled()) {
            return demoMockDataService.createGroup(dto);
        }
        return groupService.createGroup(dto);
    }

    // ユーザ登録
    @Operation(summary = "新規ユーザをグループに追加します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @PostMapping("/{groupId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDTO addUserToGroup(@PathVariable String groupId, @RequestBody UserCreateDTO dto) {
        if (demoMockDataService.isEnabled()) {
            return demoMockDataService.addUserToGroup(groupId, dto);
        }
        return groupService.addUserToGroup(groupId, dto);
    }

    // グループ削除
    @Operation(summary = "既存グループを削除します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @PostMapping("/{groupId}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable String groupId) {
        if (demoMockDataService.isEnabled()) {
            demoMockDataService.deleteGroup(groupId);
            return;
        }
        groupService.deleteGroup(groupId);
    }

    // ユーザ削除
    @Operation(summary = "既存ユーザを削除します")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "正常終了"),
        @ApiResponse(responseCode = "500", description = "サーバーエラー") })
    @PostMapping("/{groupId}/users/{userCode}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserFromGroup(@PathVariable String groupId, @PathVariable String userCode) {
        if (demoMockDataService.isEnabled()) {
            demoMockDataService.deleteUserFromGroup(groupId, userCode);
            return;
        }
        groupService.deleteUserFromGroup(groupId, userCode);
    }
}
