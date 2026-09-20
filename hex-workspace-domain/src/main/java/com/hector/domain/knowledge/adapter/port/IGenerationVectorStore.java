package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO;

import java.util.List;

/** 仅负责单个 Generation 的隔离向量写入与完整性验证。 */
public interface IGenerationVectorStore {

    /**
     * 创建或校验目标 Collection 的向量空间配置。
     * 已存在的 Collection 必须与当前维度和距离度量完全兼容。
     *
     * @param dimension 向量维度
     * @param distance 距离度量
     * @param collectionName Collection 名称
     */
    void ensureCollection(Integer dimension, String distance, String collectionName);

    /**
     * 写入一个 Generation 的向量点。
     * Point ID 由调用方稳定生成，重复调用应覆盖同一向量点而非产生重复数据。
     *
     * @param collectionName Collection 名称
     * @param points 本批待写入的 Generation 向量点
     */
    void upsert(String collectionName, List<GenerationVectorPointVO> points);

    /**
     * 精确统计指定 Generation 已写入的向量点数量。
     * 用于在发布前验证 Chunk 产物没有漏写。
     *
     * @param collectionName Collection 名称
     * @param generationId Generation 唯一标识
     * @return 当前 Generation 的向量点数量
     */
    long countByGeneration(String collectionName, String generationId);

    /**
     * 回读校验指定 Generation 的完整 Point 集合。
     * 必须同时拒绝缺失 Point、意外 Point 和 Generation 归属不匹配的 Point。
     *
     * @param collectionName Collection 名称
     * @param generationId Generation 唯一标识
     * @param expectedPointIds 由 Generation 与 Chunk 编号稳定生成的完整 Point ID 集合
     */
    void verifyGenerationPoints(String collectionName, String generationId, List<String> expectedPointIds);
}
