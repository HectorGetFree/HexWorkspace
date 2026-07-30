package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentParseStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档版本实体。
 * 每条记录代表一个不可变内容快照；解析文本以派生文件形式与该版本关联。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersionEntity {

    private Long id;

    private Long documentId;

    private Integer versionNumber;

    private Long fileId;

    private String contentHash;

    private Long extractedFileId;

    private DocumentParseStatusVO parseStatus;

    private String parserVersion;

    private LocalDateTime parsedAt;

    private String changeSummary;

    private Long createdByUserId;

    private LocalDateTime createdAt;
}
