package com.hector.domain.knowledge.model.valobj;

/** 受控评测入口返回的生产检索结果、阶段耗时和候选诊断。 */
public record RetrievalEvaluationResultVO(
        RetrievalResultVO result,
        RetrievalTimingVO timing,
        RetrievalEvaluationDiagnosticsVO diagnostics) {

    public RetrievalEvaluationResultVO(RetrievalResultVO result, RetrievalTimingVO timing) {
        this(result, timing, RetrievalEvaluationDiagnosticsVO.empty());
    }
}
