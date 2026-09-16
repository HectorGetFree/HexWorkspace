package com.hector.domain.knowledge.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 不可变 Embedding 向量空间配置快照。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingConfigEntity {
    /** 主键。 */
    private Long id;

    /** 配置内容摘要，用于唯一标识不可变配置。 */
    private String configHash;

    /** 向量服务提供商。 */
    private String provider;

    /** Embedding 模型名称。 */
    private String model;

    /** 向量维度。 */
    private Integer dimension;

    /** 向量距离度量方式。 */
    private String distance;

    /** 对应向量集合名称。 */
    private String collectionName;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
