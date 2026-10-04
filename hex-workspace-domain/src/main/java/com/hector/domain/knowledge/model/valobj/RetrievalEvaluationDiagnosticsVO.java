package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 受控评测接口的候选诊断结果。 */
public record RetrievalEvaluationDiagnosticsVO(
        List<RetrievalEvaluationCandidateVO> qdrantCandidates,
        List<RetrievalEvaluationCandidateVO> authorityValidCandidates,
        List<RetrievalEvaluationCandidateVO> rerankedCandidates,
        List<RetrievalEvaluationCandidateVO> finalCandidates) {

    public RetrievalEvaluationDiagnosticsVO {
        qdrantCandidates = qdrantCandidates == null ? List.of() : List.copyOf(qdrantCandidates);
        authorityValidCandidates = authorityValidCandidates == null ? List.of() : List.copyOf(authorityValidCandidates);
        rerankedCandidates = rerankedCandidates == null ? List.of() : List.copyOf(rerankedCandidates);
        finalCandidates = finalCandidates == null ? List.of() : List.copyOf(finalCandidates);
    }

    public RetrievalEvaluationDiagnosticsVO(
            List<RetrievalEvaluationCandidateVO> qdrantCandidates,
            List<RetrievalEvaluationCandidateVO> authorityValidCandidates,
            List<RetrievalEvaluationCandidateVO> finalCandidates) {
        this(qdrantCandidates, authorityValidCandidates, List.of(), finalCandidates);
    }

    public static RetrievalEvaluationDiagnosticsVO empty() {
        return new RetrievalEvaluationDiagnosticsVO(List.of(), List.of(), List.of(), List.of());
    }
}
