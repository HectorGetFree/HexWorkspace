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

    /**
     * 标记版本进入解析中状态。
     * 解析状态属于版本快照，避免异步任务覆盖文档其他版本的处理结果。
     */
    public void markParsing() {
        this.parseStatus = DocumentParseStatusVO.PROCESSING;
    }

    /**
     * 记录解析成功后的派生文件与解析器信息。
     */
    public void markParseSucceeded(Long extractedFileId, String parserVersion, LocalDateTime parsedAt) {
        this.extractedFileId = extractedFileId;
        this.parserVersion = parserVersion;
        this.parsedAt = parsedAt;
        this.parseStatus = DocumentParseStatusVO.SUCCESS;
    }

    /**
     * 标记版本解析失败，保留解析器版本以支持后续定位和重试。
     */
    public void markParseFailed(String parserVersion) {
        this.parserVersion = parserVersion;
        this.parseStatus = DocumentParseStatusVO.FAILED;
    }
}
