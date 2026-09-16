package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.Folder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 文件夹 MyBatis DAO，与 folder.xml 中的语句保持一一对应。 */
@Mapper
public interface IFolderDao {

    int insert(Folder folder);

    int update(Folder folder);

    Folder queryById(@Param("id") Long id);

    Folder queryRootByWorkspaceId(@Param("workspaceId") Long workspaceId);

    List<Folder> queryAncestorChain(@Param("folderId") Long folderId);

    Folder queryActiveByParentIdAndName(@Param("workspaceId") Long workspaceId,
                                        @Param("parentId") Long parentId,
                                        @Param("name") String name);

    List<Folder> queryActiveByParentId(@Param("workspaceId") Long workspaceId,
                                       @Param("parentId") Long parentId);
}
