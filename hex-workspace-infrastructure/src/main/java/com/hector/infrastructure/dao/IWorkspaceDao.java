package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Workspace DAO。 */
@Mapper
public interface IWorkspaceDao {

    int insert(Workspace workspace);

    Workspace queryWorkspaceByCode(@Param("workspaceCode") String workspaceCode);

    Workspace queryWorkspaceByOwnerUserIdAndType(@Param("ownerUserId") Long ownerUserId,
                                                  @Param("workspaceType") String workspaceType);
}
