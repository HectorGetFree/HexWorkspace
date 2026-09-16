package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文档表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    private Long id;
    private String documentCode;
    private Long workspaceId;
    private Long parentId;
    private Long ownerUserId;
    private String title;
    private String fileName;
    private Integer documentType;
    private Integer status;
    private Long currentVersionNo;

    /** 影响知识索引输入的单调修订号。 */
    private Long indexInputRevision;

    /** 版本生效条件更新中预期的当前版本号；仅用于 Mapper 条件，不落库。 */
    @Setter
    private Long expectedVersionNo;

    /** 索引修订条件更新中预期的修订号；仅用于 Mapper 条件，不落库。 */
    @Setter
    private Long expectedIndexInputRevision;
    private String idempotencyKey;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
