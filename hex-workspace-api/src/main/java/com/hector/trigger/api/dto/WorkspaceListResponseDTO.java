package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** 当前用户拥有的 Workspace 列表响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceListResponseDTO implements Serializable {

    private List<WorkspaceResponseDTO> workspaces;
}
