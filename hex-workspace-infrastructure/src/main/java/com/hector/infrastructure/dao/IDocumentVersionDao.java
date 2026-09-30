package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 文档版本 DAO。 */
@Mapper
public interface IDocumentVersionDao {

    int insert(DocumentVersion version);

    DocumentVersion queryById(@Param("id") Long id);

    DocumentVersion queryByDocumentIdAndVersionNo(@Param("documentId") Long documentId,
                                                  @Param("versionNo") Long versionNo);
}
