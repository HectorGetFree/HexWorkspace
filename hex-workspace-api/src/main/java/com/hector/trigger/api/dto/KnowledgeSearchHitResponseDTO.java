package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 知识库检索命中项。
 * 作为对外协议对象，后续问答接口可复用它返回真实来源，而不信任模型自行编造引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchHitResponseDTO implements Serializable {

    private Long documentId;

    private String documentCode;

    private Integer chunkNo;

    private String heading;

    private String content;

    private Double score;
}
