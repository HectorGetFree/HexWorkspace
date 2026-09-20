package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;

/** 仅供内部 AI 调用的可信知识检索服务。 */
public interface IKnowledgeRetrievalService {

    /**
     * 执行一次经过 MySQL 权威状态复核的内部知识检索。
     *
     * @param context 检索用户与 Workspace 上下文
     * @param query 查询文本
     * @param options 召回与结果限制参数
     * @return 可交付给内部 AI 的可信检索结果
     */
    RetrievalResultVO retrieve(RetrievalContextVO context, String query, RetrievalOptionsVO options);
}
