package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** knowledge_embedding_config 表映射对象。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingConfig {
    private Long id;
    private String configHash;
    private String provider;
    private String model;
    private Integer dimension;
    private String distance;
    private String collectionName;
    private LocalDateTime createdAt;
}
