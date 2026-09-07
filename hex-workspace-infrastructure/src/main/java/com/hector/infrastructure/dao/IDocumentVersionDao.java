package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档版本 DAO。
 */
@Mapper
public interface IDocumentVersionDao {

    int insert(DocumentVersion documentVersion);

    int updateParseResult(DocumentVersion documentVersion);

    DocumentVersion queryByDocumentIdAndVersionNumber(@Param("documentId") Long documentId,
                                                      @Param("versionNumber") Integer versionNumber);

    List<DocumentVersion> queryByDocumentId(@Param("documentId") Long documentId);
}
