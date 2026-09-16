package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档 DAO。
 */
@Mapper
public interface IDocumentDao {

    int insert(Document document);

    int update(Document document);

    int activateVersionIfCurrent(Document document);

    int startWritingIfCurrent(Document document);

    int renameAndAdvanceIndexRevisionIfCurrent(Document document);

    Document queryById(@Param("id") Long id);

    Document queryByDocumentCode(@Param("documentCode") String documentCode);

    Document queryByUploadIdempotencyKey(@Param("workspaceId") Long workspaceId,
                                         @Param("ownerUserId") Long ownerUserId,
                                         @Param("idempotencyKey") String idempotencyKey);

    List<Document> queryByWorkspaceId(@Param("workspaceId") Long workspaceId);

    List<Document> queryVisibleByParentId(@Param("workspaceId") Long workspaceId, @Param("parentId") Long parentId);

    Document queryActiveByParentIdAndFileName(@Param("workspaceId") Long workspaceId,
                                              @Param("parentId") Long parentId,
                                              @Param("fileName") String fileName);
}
