package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 文件夹表持久化对象，仅负责 MyBatis 字段映射。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Folder {

    /** 数据库主键。 */
    private Long id;

    /** 文件夹对外业务编码。 */
    private String folderCode;

    /** 所属 Workspace ID。 */
    private Long workspaceId;

    /** 父文件夹 ID；根目录为空。 */
    private Long parentId;

    /** 文件夹名称。 */
    private String name;

    /** 是否为 Workspace 根目录。 */
    private Boolean root;

    /** 文件夹状态。 */
    private String status;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
