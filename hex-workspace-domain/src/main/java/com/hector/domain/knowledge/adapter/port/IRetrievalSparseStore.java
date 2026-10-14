package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import java.util.List;

/** 非权威稀疏召回；返回后必须通过检索服务的权限与 Generation 复核。 */
public interface IRetrievalSparseStore {
    List<RetrievalCandidateVO> searchCandidates(Long workspaceId, Long configId, String query, int limit);
    /** 非阻塞安排已有快照刷新，供定时触发入口调用。 */
    void refreshKnownWorkspaces();
    /** 内部评测就绪检查；可安排冷启动构建，不调用 Embedding 或 Rerank。 */
    java.util.Map<String, Object> prepareAndStatus(Long workspaceId);
}
