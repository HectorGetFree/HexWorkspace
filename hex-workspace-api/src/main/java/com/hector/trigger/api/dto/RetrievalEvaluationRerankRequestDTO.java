package com.hector.trigger.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 仅供离线评测将已验证的 Active Generation Chunk 交给现有 Reranker。 */
@Data
public class RetrievalEvaluationRerankRequestDTO implements Serializable {

    private Long workspaceId;

    private String query;

    private Integer topK;

    /**
     * 仅供离线 Rerank 输入实验选择权威 Chunk 的组装形态：
     * RAW_CHUNK 或 STRUCTURAL_CONTEXT。
     */
    private String inputVariant;

    private List<CandidateDTO> candidates;

    @Data
    public static class CandidateDTO implements Serializable {

        private String documentCode;

        private String generationId;

        private Integer chunkNo;

        /** 仅用于固定 RRF 输入次序和 rerank 同分时的稳定回退。 */
        private Integer rank;

        private Double score;
    }
}
