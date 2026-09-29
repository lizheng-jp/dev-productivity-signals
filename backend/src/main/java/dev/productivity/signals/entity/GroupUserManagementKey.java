package dev.productivity.signals.entity;

import java.io.Serializable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class GroupUserManagementKey implements Serializable {

    @Column(name = "group_id")
    private String groupId;

    @Column(name = "user_code")
    private String userCode;
}
