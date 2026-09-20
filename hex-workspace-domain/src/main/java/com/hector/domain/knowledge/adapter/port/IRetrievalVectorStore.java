package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;

import java.util.List;

/** 按 Workspace Filter 从指定向量空间召回非权威候选。 */
public interface IRetrievalVectorStore {
    List<RetrievalCandidateVO> searchCandidates(String collectionName, Long workspaceId, List<Float> queryVector, int limit);
}
