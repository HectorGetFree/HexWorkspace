package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeIndexState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 文档权威 Generation 指针表的 MyBatis DAO。 */
@Mapper
public interface IKnowledgeIndexStateDao {
    /** 查询文档当前已发布的 Generation 指针。 */
    KnowledgeIndexState queryByDocumentId(@Param("documentId") Long documentId);

    /** 新建或更新文档的权威 Generation 指针。 */
    int upsertActive(@Param("documentId") Long documentId, @Param("generationId") String generationId);
}
