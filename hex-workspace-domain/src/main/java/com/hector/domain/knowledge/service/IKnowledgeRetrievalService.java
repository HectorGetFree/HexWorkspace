package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalExecutionVO;
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

    /**
     * 在保持相同检索语义的前提下，附带服务端阶段耗时。
     * 默认实现兼容其他实现方；生产实现会覆盖以提供真实分段数据。
     */
    default RetrievalExecutionVO retrieveWithTrace(
            RetrievalContextVO context, String query, RetrievalOptionsVO options) {
        long startedAt = System.nanoTime();
        RetrievalResultVO result = retrieve(context, query, options);
        double totalMs = (System.nanoTime() - startedAt) / 1_000_000D;
        return new RetrievalExecutionVO(result,
                new com.hector.domain.knowledge.model.valobj.RetrievalTimingVO(0, 0, 0, 0, totalMs));
    }

    /**
     * 仅供 service-token 保护的离线评测读取候选阶段诊断；普通产品调用不得依赖该信息。
     * 默认实现保持兼容，未实现诊断能力的实现方只返回阶段耗时。
     */
    default RetrievalExecutionVO retrieveWithEvaluationTrace(
            RetrievalContextVO context, String query, RetrievalOptionsVO options) {
        return retrieveWithTrace(context, query, options);
    }
}
