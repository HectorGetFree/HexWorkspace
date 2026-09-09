package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 文件夹新建、重命名、移动等操作的请求参数。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderCommandRequestDTO implements Serializable {

    /** 新建文件夹时所在的父文件夹 ID。 */
    private Long parentId;

    /** 移动文件夹时目标父文件夹 ID。 */
    private Long targetParentId;

    /** 文件夹名称。 */
    private String name;
}
