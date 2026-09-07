package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Workspace 表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {

    private Long id;
    private String workspaceCode;
    private String workspaceName;
    private String workspaceType;
    private String status;
    private Long ownerUserId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
