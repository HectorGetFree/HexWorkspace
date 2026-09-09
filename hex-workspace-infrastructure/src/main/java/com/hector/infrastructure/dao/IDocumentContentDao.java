package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档内容快照 DAO。
 */
@Mapper
public interface IDocumentContentDao {

    /**
     * 新增内容快照。
     */
    int insert(DocumentContent content);

    /**
     * 更新内容快照。
     */
    int update(DocumentContent content);

    /**
     * 按主键查询内容快照。
     */
    DocumentContent queryById(@Param("id") Long id);

    /**
     * 按业务编码查询内容快照。
     */
    DocumentContent queryByContentCode(@Param("contentCode") String contentCode);

    /**
     * 按 Workspace ID 查询内容快照列表。
     */
    List<DocumentContent> queryByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
