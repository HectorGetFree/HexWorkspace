package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** 知识库检索响应，返回可用于问答上下文和来源展示的命中片段。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchResponseDTO implements Serializable {

    private List<KnowledgeSearchHitResponseDTO> hits;
}
