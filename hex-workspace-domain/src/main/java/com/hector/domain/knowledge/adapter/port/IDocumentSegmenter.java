package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.KnowledgeSegmentVO;

import java.util.List;

/**
 * 文档分段端口。领域仅描述“如何形成可检索语义片段”，不依赖具体文本解析或向量 SDK。
 */
public interface IDocumentSegmenter {

    List<KnowledgeSegmentVO> segment(String content);

    String strategyVersion();
}
