package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.KnowledgeAnswerVO;

/** 基于Workspace知识索引进行单轮问答的领域服务。 */
public interface IKnowledgeAnswerService {

    KnowledgeAnswerVO answer(Long workspaceId, String question);
}
