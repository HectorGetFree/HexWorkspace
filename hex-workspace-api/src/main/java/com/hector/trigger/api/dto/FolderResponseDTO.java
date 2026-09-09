package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Workspace 文件夹响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderResponseDTO implements Serializable {

    /** 文件夹数据库主键。 */
    private Long folderId;

    /** 文件夹对外业务编码。 */
    private String folderCode;

    /** 文件夹所属 Workspace ID。 */
    private Long workspaceId;

    /** 父文件夹 ID；根目录为空。 */
    private Long parentId;

    /** 文件夹名称。 */
    private String name;

    /** 是否为 Workspace 根目录。 */
    private Boolean root;

    /** 文件夹状态。 */
    private String status;
}
