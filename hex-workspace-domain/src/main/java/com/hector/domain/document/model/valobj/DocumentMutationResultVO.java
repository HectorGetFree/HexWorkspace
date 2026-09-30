package com.hector.domain.document.model.valobj;

/** 文档版本正式生效后的最小写入结果。 */
public record DocumentMutationResultVO(Long documentId, Long previousVersionId, Long versionId) {
}
