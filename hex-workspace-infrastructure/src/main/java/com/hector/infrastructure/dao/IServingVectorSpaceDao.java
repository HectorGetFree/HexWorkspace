package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.EmbeddingConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Serving Vector Space 单例状态的 MyBatis DAO。 */
@Mapper
public interface IServingVectorSpaceDao {

    /** 查询当前权威 Embedding 配置。 */
    EmbeddingConfig queryServingConfig();

    /** 仅当单例记录不存在时初始化权威配置。 */
    int insertIgnore(@Param("embeddingConfigId") Long embeddingConfigId);
}
