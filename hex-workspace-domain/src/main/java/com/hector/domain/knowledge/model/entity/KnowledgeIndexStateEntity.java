package com.hector.domain.knowledge.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 文档当前已发布 Generation 的唯一权威指针。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexStateEntity {
    /** 文档主键，同时为该表主键。 */
    private Long documentId;

    /** 当前对外可用的 Generation 标识；未完成构建时为空。 */
    private String activeGenerationId;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
