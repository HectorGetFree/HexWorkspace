package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** knowledge_parse_artifact 表映射对象。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeParseArtifact {
    private Long id;
    private Long documentId;
    private Long versionNo;
    private String sourceChecksum;
    private String parserVersion;
    private String schemaVersion;
    private String artifactKey;
    private String artifactChecksum;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
