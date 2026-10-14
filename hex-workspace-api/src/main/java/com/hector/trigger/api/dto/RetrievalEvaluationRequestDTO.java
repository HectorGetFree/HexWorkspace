package com.hector.trigger.api.dto;

import lombok.Data;

import java.io.Serializable;

/** 当前 RAG 检索评测的内部请求。 */
@Data
public class RetrievalEvaluationRequestDTO implements Serializable {

    private Long workspaceId;

    private String query;

    /** Qdrant 初始候选池上限；仅供受控离线评测比较召回深度。 */
    private Integer candidateLimit;

    /** 最终经过权威校验和单文档去重后返回的结果数。 */
    private Integer topK;
    /** 仅 /ab 使用 A 或 B；普通检索端点忽略该字段。 */
    private String variant;
}
