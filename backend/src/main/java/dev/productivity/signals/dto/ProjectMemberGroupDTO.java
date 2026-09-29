package dev.productivity.signals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberGroupDTO {
    private String userCode;
    private String userName;
    private String groupId;
    private String groupName;
}
