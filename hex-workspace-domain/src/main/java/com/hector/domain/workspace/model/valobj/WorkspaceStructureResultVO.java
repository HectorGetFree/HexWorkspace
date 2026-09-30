package com.hector.domain.workspace.model.valobj;

import java.util.List;

/** 有界 Workspace 结构读取结果；truncated 表示仍有未包含的可见直属资源。 */
public record WorkspaceStructureResultVO(
        List<WorkspaceStructureItemVO> items,
        boolean truncated) {
}
