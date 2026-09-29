package dev.productivity.signals.dto;

import lombok.Data;

@Data
public class UserDTO {
    private String userCode;
    private String userName;

    public UserDTO(String userCode, String userName) {
        this.userCode = userCode;
        this.userName = userName;
    }
}
