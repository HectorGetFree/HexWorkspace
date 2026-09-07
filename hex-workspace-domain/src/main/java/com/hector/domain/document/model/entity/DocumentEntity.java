package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档聚合根。
 * 保存文档的租户归属、当前状态和当前版本指针，不承载文件存储或 AI 处理细节。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    private Long id;

    private String documentCode;

    private Long tenantId;

    private Long ownerUserId;

    private Long sourceFileId;

    private String title;

    private DocumentTypeVO documentType;

    private DocumentStatusVO status;

    private Integer currentVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void archive() {
        this.status = DocumentStatusVO.ARCHIVED;
    }

    public void activate() {
        this.status = DocumentStatusVO.ACTIVE;
    }

    public void delete() {
        this.status = DocumentStatusVO.DELETED;
    }

    public void switchCurrentVersion(Integer versionNumber) {
        this.currentVersion = versionNumber;
    }

    public boolean isActive() {
        return DocumentStatusVO.ACTIVE == this.status;
    }
}
