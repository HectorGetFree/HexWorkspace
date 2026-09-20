package com.hector.domain.knowledge.model.valobj;

/** 内部检索的有界召回与结果截断参数。 */
public record RetrievalOptionsVO(
        Integer candidateLimit,
        Integer maxChunksPerDocument,
        Integer resultLimit) {
    private static final int DEFAULT_CANDIDATE_LIMIT = 30;
    private static final int DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 2;
    private static final int DEFAULT_RESULT_LIMIT = 6;

    public static RetrievalOptionsVO defaults() {
        return new RetrievalOptionsVO(DEFAULT_CANDIDATE_LIMIT, DEFAULT_MAX_CHUNKS_PER_DOCUMENT, DEFAULT_RESULT_LIMIT);
    }

    public RetrievalOptionsVO normalized() {
        int candidates = candidateLimit == null ? DEFAULT_CANDIDATE_LIMIT : candidateLimit;
        int perDocument = maxChunksPerDocument == null ? DEFAULT_MAX_CHUNKS_PER_DOCUMENT : maxChunksPerDocument;
        int results = resultLimit == null ? DEFAULT_RESULT_LIMIT : resultLimit;
        if (candidates < 1 || candidates > 100 || perDocument < 1 || perDocument > 10 || results < 1 || results > 20) {
            throw new IllegalArgumentException("检索参数超出允许范围");
        }
        return new RetrievalOptionsVO(candidates, perDocument, results);
    }
}
