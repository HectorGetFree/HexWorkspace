package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Workspace DAO。 */
@Mapper
public interface IWorkspaceDao {

    int insert(Workspace workspace);

    Workspace queryWorkspaceByCode(@Param("workspaceCode") String workspaceCode);

    Workspace queryDefaultWorkspaceByOwnerUserId(@Param("ownerUserId") Long ownerUserId);

    List<Workspace> queryWorkspacesByOwnerUserId(@Param("ownerUserId") Long ownerUserId);
}
