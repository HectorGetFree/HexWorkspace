package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 文档新建、编辑、移动等操作的请求参数。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentCommandRequestDTO implements Serializable {

    /** 新建文档时所在的父文件夹 ID。 */
    private Long parentId;

    /** 移动文档时目标父文件夹 ID。 */
    private Long targetParentId;

    /** 文档完整文件名；平台新建文档会自动补齐 .md 后缀。 */
    private String fileName;

    /** Markdown 文档正文。 */
    private String content;
}
