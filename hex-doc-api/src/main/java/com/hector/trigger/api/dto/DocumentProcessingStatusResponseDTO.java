package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档当前版本的异步处理状态。
 * 供上传客户端和评测执行器轮询，不暴露内部消息或向量库细节。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProcessingStatusResponseDTO implements Serializable {

    private String documentCode;
    private Integer versionNumber;
    private String documentStatus;
    private String parseStatus;
    private Long extractedFileId;
    private String parserVersion;
    private LocalDateTime parsedAt;
}
