package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentParseStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档版本实体。
 * 每条记录代表一个不可变内容快照；解析文本以派生内容形式与该版本关联。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersionEntity {

    /** 文档版本主键 ID。 */
    private Long id;

    /** 该版本所属的文档 ID。 */
    private Long documentId;

    /** 文档内单调递增的版本号。 */
    private Integer versionNumber;

    /** 当前版本对应的内容快照 ID。 */
    private Long contentId;

    /** 当前版本内容摘要，用于完整性校验和幂等判断。 */
    private String contentHash;

    /** 文档解析后生成的纯文本派生内容快照 ID。 */
    private Long extractedContentId;

    /** 当前版本的知识解析处理状态。 */
    private DocumentParseStatusVO parseStatus;

    /** 生成解析结果时使用的解析器版本。 */
    private String parserVersion;

    /** 当前版本完成解析的时间。 */
    private LocalDateTime parsedAt;

    /** 当前版本相对上一版本的变更说明。 */
    private String changeSummary;

    /** 创建当前版本的用户 ID。 */
    private Long createdByUserId;

    /** 当前版本创建时间。 */
    private LocalDateTime createdAt;

    /**
     * 标记版本进入解析中状态。
     * 解析状态属于版本快照，避免异步任务覆盖文档其他版本的处理结果。
     */
    public void markParsing() {
        this.parseStatus = DocumentParseStatusVO.PROCESSING;
    }

    /**
     * 记录解析成功后的派生内容与解析器信息。
     */
    public void markParseSucceeded(Long extractedContentId, String parserVersion, LocalDateTime parsedAt) {
        this.extractedContentId = extractedContentId;
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
