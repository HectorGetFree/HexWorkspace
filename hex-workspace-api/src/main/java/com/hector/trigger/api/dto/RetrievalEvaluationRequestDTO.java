package com.hector.trigger.api.dto;

import lombok.Data;

import java.io.Serializable;

/** 当前 RAG 检索评测的内部请求。 */
@Data
public class RetrievalEvaluationRequestDTO implements Serializable {

    private Long workspaceId;

    private String query;

    private Integer topK;
}
