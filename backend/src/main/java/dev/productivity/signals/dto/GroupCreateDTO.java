package dev.productivity.signals.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GroupCreateDTO {
    private String groupId;
    private String groupName;
}