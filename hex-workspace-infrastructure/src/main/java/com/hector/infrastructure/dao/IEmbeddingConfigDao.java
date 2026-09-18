package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.EmbeddingConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Embedding 配置快照表的 MyBatis DAO。 */
@Mapper
public interface IEmbeddingConfigDao {
    /** 按配置摘要幂等插入快照。 */
    int insertIgnore(EmbeddingConfig config);

    /** 按主键查询快照。 */
    EmbeddingConfig queryById(@Param("id") Long id);

    /** 按配置内容摘要查询快照。 */
    EmbeddingConfig queryByConfigHash(@Param("configHash") String configHash);
}
