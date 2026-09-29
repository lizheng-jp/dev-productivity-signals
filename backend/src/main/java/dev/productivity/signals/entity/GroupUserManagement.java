package dev.productivity.signals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "group_user_management")
public class GroupUserManagement {

    @EmbeddedId
    private GroupUserManagementKey id;

    @Column(name = "user_name")
    private String userName;
}
