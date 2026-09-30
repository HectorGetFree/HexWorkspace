package com.hector.domain.document.model.valobj;

/** 指定 DocumentVersion 的有界文本读取结果。 */
public record DocumentReadResultVO(
        Long documentId,
        Long versionId,
        String documentTitle,
        int startLine,
        int endLine,
        boolean hasMore,
        String content) {
}
