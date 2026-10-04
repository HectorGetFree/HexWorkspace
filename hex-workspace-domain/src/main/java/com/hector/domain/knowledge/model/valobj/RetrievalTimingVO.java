package com.hector.domain.knowledge.model.valobj;

/** 一次检索在关键边界的服务端耗时，单位为毫秒。 */
public record RetrievalTimingVO(
        double embeddingMs,
        double qdrantMs,
        double authorityInitialMs,
        double rerankMs,
        double authorityRecheckMs,
        double totalMs) {

    public RetrievalTimingVO(
            double embeddingMs,
            double qdrantMs,
            double authorityInitialMs,
            double authorityRecheckMs,
            double totalMs) {
        this(embeddingMs, qdrantMs, authorityInitialMs, 0D, authorityRecheckMs, totalMs);
    }
}
