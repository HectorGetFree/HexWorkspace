package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.RetrievalRerankCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankScoreVO;

import java.util.List;

/**
 * 对已通过权威校验的检索候选进行相关性重排。
 * Reranker 不能产生新候选，也不承担 Workspace、版本或 Generation 的授权判断。
 */
public interface IRetrievalReranker {

    boolean isEnabled();

    List<RetrievalRerankScoreVO> rerank(String query, List<RetrievalRerankCandidateVO> candidates);
}
