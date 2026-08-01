package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** 知识库检索响应，仅返回 MVP 问答编排所需的正文与引用信息。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchResponseDTO {
    private List<Hit> hits;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Hit {
        private Long documentId;
        private Integer versionNumber;
        private Integer chunkNo;
        private String heading;
        private String content;
        private Double score;
    }
}
