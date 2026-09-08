package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Workspace 响应对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceResponseDTO implements Serializable {

    private Long workspaceId;
    private String workspaceCode;
    private String workspaceName;
    private Boolean isDefault;
    private String status;
    private Long ownerUserId;
    private String description;
}
