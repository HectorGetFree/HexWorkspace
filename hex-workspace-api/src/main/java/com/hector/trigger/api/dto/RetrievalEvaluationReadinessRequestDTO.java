package com.hector.trigger.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 评测语料进入当前 Active Generation 的就绪检查请求。 */
@Data
public class RetrievalEvaluationReadinessRequestDTO implements Serializable {

    private Long workspaceId;

    private List<String> documentCodes;
}
