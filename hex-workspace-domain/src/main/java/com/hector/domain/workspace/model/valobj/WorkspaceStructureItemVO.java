package com.hector.domain.workspace.model.valobj;

/** Workspace 目录结构中的一个直属资源。 */
public record WorkspaceStructureItemVO(
        String type,
        Long id,
        String name,
        // 文档的不可变版本记录主键；文件夹为空，不是文档内版本号。
        Long versionId) {
}
