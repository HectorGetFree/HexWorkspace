package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;

/** 从指定不可变 DocumentVersion 精确加载待解析文件。 */
public interface IDocumentLoader {

    LoadedDocumentVO load(Long workspaceId, Long documentId, Long versionNo);
}
