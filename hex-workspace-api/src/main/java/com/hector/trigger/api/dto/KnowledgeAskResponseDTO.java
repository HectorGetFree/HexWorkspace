package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** 单轮知识库问答响应，sources 始终由后端实际检索结果构成。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeAskResponseDTO implements Serializable {

    private String answer;

    private List<KnowledgeSearchHitResponseDTO> sources;
}
