package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 知识库问答结果。sources 由后端检索链路确定，不使用模型自行生成的引用。 */
public record KnowledgeAnswerVO(String answer, List<KnowledgeSearchHitVO> sources) {
}
