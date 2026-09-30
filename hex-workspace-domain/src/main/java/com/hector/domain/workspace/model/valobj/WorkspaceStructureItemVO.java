package com.hector.domain.workspace.model.valobj;

/** Workspace 目录结构中的一个直属资源。 */
public record WorkspaceStructureItemVO(
        String type,
        Long id,
        String name,
        Long versionId) {
}
