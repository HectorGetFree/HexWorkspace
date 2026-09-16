package com.hector.domain.knowledge.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 版本化解析产物元数据；实际 Parser 与产物写入在 Phase 2 实现。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeParseArtifactEntity {
    /** 主键。 */
    private Long id;

    /** 所属文档。 */
    private Long documentId;

    /** 解析来源的不可变内容版本号。 */
    private Long versionNo;

    /** 来源内容摘要。 */
    private String sourceChecksum;

    /** 解析器版本。 */
    private String parserVersion;

    /** 产物数据结构版本。 */
    private String schemaVersion;

    /** 解析产物对象存储路径。 */
    private String artifactKey;

    /** 解析产物摘要。 */
    private String artifactChecksum;

    /** 产物状态，0=BUILDING、1=READY、2=FAILED。 */
    private Integer status;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
