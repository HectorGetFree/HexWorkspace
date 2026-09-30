package com.hector.domain.document.adapter.port;

/** 读取指定 DocumentVersion 已生成的模型可读文本表示。 */
public interface IDocumentVersionTextReader {

    /**
     * 读取指定版本的 Parsed Artifact。
     *
     * @throws RuntimeException 版本产物不存在、无效或校验失败
     */
    String read(Long documentId, Long versionNo, String sourceChecksum);
}
