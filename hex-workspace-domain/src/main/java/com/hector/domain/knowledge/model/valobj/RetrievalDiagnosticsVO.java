package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 检索阶段的文档级候选排名；不参与产品检索结果和授权判定。 */
public record RetrievalDiagnosticsVO(
        List<RetrievalCandidateRankVO> qdrantCandidates,
        List<RetrievalCandidateRankVO> authorityValidCandidates,
        List<RetrievalCandidateRankVO> rerankedCandidates,
        List<RetrievalCandidateRankVO> finalCandidates) {

    public RetrievalDiagnosticsVO {
        qdrantCandidates = qdrantCandidates == null ? List.of() : List.copyOf(qdrantCandidates);
        authorityValidCandidates = authorityValidCandidates == null ? List.of() : List.copyOf(authorityValidCandidates);
        rerankedCandidates = rerankedCandidates == null ? List.of() : List.copyOf(rerankedCandidates);
        finalCandidates = finalCandidates == null ? List.of() : List.copyOf(finalCandidates);
    }

    public RetrievalDiagnosticsVO(
            List<RetrievalCandidateRankVO> qdrantCandidates,
            List<RetrievalCandidateRankVO> authorityValidCandidates,
            List<RetrievalCandidateRankVO> finalCandidates) {
        this(qdrantCandidates, authorityValidCandidates, List.of(), finalCandidates);
    }

    public static RetrievalDiagnosticsVO empty() {
        return new RetrievalDiagnosticsVO(List.of(), List.of(), List.of(), List.of());
    }
}
