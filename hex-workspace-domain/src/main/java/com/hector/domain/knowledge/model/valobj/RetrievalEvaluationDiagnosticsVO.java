package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 受控评测接口的候选诊断结果。 */
public record RetrievalEvaluationDiagnosticsVO(
        List<RetrievalEvaluationCandidateVO> qdrantCandidates,
        List<RetrievalEvaluationCandidateVO> authorityValidCandidates,
        List<RetrievalEvaluationCandidateVO> rerankedCandidates,
        List<RetrievalEvaluationCandidateVO> finalCandidates,
        List<RetrievalEvaluationCandidateVO> sparseCandidates,
        List<RetrievalEvaluationCandidateVO> fusedCandidates,
        List<RetrievalEvaluationCandidateVO> rerankedChunks) {

    public RetrievalEvaluationDiagnosticsVO {
        qdrantCandidates = qdrantCandidates == null ? List.of() : List.copyOf(qdrantCandidates);
        authorityValidCandidates = authorityValidCandidates == null ? List.of() : List.copyOf(authorityValidCandidates);
        rerankedCandidates = rerankedCandidates == null ? List.of() : List.copyOf(rerankedCandidates);
        finalCandidates = finalCandidates == null ? List.of() : List.copyOf(finalCandidates);
        sparseCandidates = sparseCandidates == null ? List.of() : List.copyOf(sparseCandidates);
        fusedCandidates = fusedCandidates == null ? List.of() : List.copyOf(fusedCandidates);
        rerankedChunks = rerankedChunks == null ? List.of() : List.copyOf(rerankedChunks);
    }

    public RetrievalEvaluationDiagnosticsVO(List<RetrievalEvaluationCandidateVO> dense, List<RetrievalEvaluationCandidateVO> valid,
            List<RetrievalEvaluationCandidateVO> reranked, List<RetrievalEvaluationCandidateVO> result) {
        this(dense, valid, reranked, result, List.of(), List.of(), List.of());
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
