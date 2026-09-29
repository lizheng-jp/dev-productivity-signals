package dev.productivity.signals.dto;

import java.util.List;

import lombok.Data;

@Data
public class GroupDTO {
    private String groupId;
    private String groupName;
    private List<UserDTO> users;

    public GroupDTO(String groupId, String groupName, List<UserDTO> users) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.users = users;
    }

}
