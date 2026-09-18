package com.hector.domain.knowledge.model.valobj;

/** 由不可变 DocumentVersion 加载出的待解析文件。 */
public record LoadedDocumentVO(
        Long workspaceId,
        Long documentId,
        Long versionNo,
        String fileName,
        String contentType,
        String sourceChecksum,
        DocumentFormatVO format,
        byte[] content) {
}
