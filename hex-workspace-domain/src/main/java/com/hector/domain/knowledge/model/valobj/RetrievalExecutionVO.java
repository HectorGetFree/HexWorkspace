package com.hector.domain.knowledge.model.valobj;

/** 检索结果及其服务端阶段耗时；诊断信息不改变检索结果或授权语义。 */
public record RetrievalExecutionVO(
        RetrievalResultVO result,
        RetrievalTimingVO timing,
        RetrievalDiagnosticsVO diagnostics) {

    public RetrievalExecutionVO(RetrievalResultVO result, RetrievalTimingVO timing) {
        this(result, timing, RetrievalDiagnosticsVO.empty());
    }
}
