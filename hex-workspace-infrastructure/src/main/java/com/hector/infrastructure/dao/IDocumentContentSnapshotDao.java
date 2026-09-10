package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentContentSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档内容快照 DAO。
 */
@Mapper
public interface IDocumentContentSnapshotDao {

    /**
     * 新增内容快照。
     */
    int insert(DocumentContentSnapshot snapshot);

    /**
     * 更新内容快照。
     */
    int update(DocumentContentSnapshot snapshot);

    /**
     * 按主键查询内容快照。
     */
    DocumentContentSnapshot queryById(@Param("id") Long id);

    /**
     * 按业务编码查询内容快照。
     */
    DocumentContentSnapshot queryByContentCode(@Param("contentCode") String contentCode);

    /**
     * 按 Workspace ID 查询内容快照列表。
     */
    List<DocumentContentSnapshot> queryByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
